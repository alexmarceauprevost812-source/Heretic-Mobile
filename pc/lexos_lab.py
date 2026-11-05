#!/usr/bin/env python3
"""Optional HTTPS companion. Trusted PC configuration supplies executable argv, never phone input."""
import concurrent.futures
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

OPERATIONS = {'quantize', 'lora', 'train', 'ablate'}


class Lab:
    def __init__(self, config, root):
        self.config = config
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        self.lock = threading.Lock()
        # Interrupted jobs cannot be safely resumed without tool-specific checkpoints.
        for path in self.root.glob('*/status.json'):
            j = json.loads(path.read_text())
            if j['state'] in ('queued', 'running'):
                j.update(state='interrupted', error='Compagnon redémarré : relancez la tâche.')
                self.save(j)

    def capabilities(self):
        return {'operations': sorted(set(self.config.get('recipes', {})) & OPERATIONS),
                'models': sorted(self.config.get('models', {})),
                'protocol': 1, 'weights': 'Seulement les recettes installées sur le PC ; aucun entraînement intégré Android.'}

    def save(self, job):
        p = self.root / job['id'] / 'status.json'
        tmp = p.with_suffix('.tmp')
        with self.lock:
            tmp.write_text(json.dumps(job, ensure_ascii=False))
            tmp.replace(p)

    def status(self, identifier):
        if not re.fullmatch('[a-f0-9]{32}', identifier):
            raise ValueError('Identifiant invalide')
        return json.loads((self.root / identifier / 'status.json').read_text())

    def submit(self, operation, model):
        if operation not in OPERATIONS or operation not in self.config.get('recipes', {}):
            raise ValueError('Opération non installée sur ce PC')
        if model not in self.config.get('models', {}):
            raise ValueError('Modèle PC inconnu')
        source = Path(self.config['models'][model]).resolve(strict=True)
        recipe = self.config['recipes'][operation]
        if not isinstance(recipe, list) or not recipe or not all(isinstance(x, str) for x in recipe):
            raise ValueError('Recette argv invalide')
        if not any('{output}' in x for x in recipe) or not any('{input}' in x for x in recipe):
            raise ValueError('La recette exige {input} et {output}')
        identifier = uuid.uuid4().hex
        directory = self.root / identifier
        directory.mkdir()
        output = directory / 'result.gguf'
        job = dict(id=identifier, operation=operation, model=model, state='queued', progress=None,
                   message='En attente du PC', created=time.time())
        self.save(job)
        self.pool.submit(self.run, job, source, output, recipe)
        return job

    def run(self, job, source, output, recipe):
        try:
            # Run against an isolated copy: even a misconfigured tool cannot overwrite the source through {input}.
            import shutil
            working_source = output.parent / ('input' + source.suffix)
            job.update(state='running', message='Copie de travail sur PC', progress=None)
            self.save(job)
            shutil.copyfile(source, working_source)
            argv = [x.replace('{input}', str(working_source)).replace('{output}', str(output)) for x in recipe]
            with subprocess.Popen(argv, cwd=output.parent, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, errors='replace', bufsize=1) as proc:
                for line in proc.stdout:
                    # A recipe can emit a JSON line {"progress": 0..100, "message": "..."}.
                    try:
                        event = json.loads(line)
                        value = event.get('progress')
                        if isinstance(value, (int, float)) and 0 <= value <= 100:
                            job['progress'] = value
                        job['message'] = str(event.get('message', line))[-2000:]
                    except (ValueError, AttributeError):
                        job['message'] = line.strip()[-2000:]
                    self.save(job)
                if proc.wait() != 0:
                    raise RuntimeError('Recette PC en échec ; consultez le dernier message')
            if not output.is_file() or output.stat().st_size == 0:
                raise RuntimeError('Aucun résultat produit à {output}')
            digest = hashlib.sha256()
            with output.open('rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            job.update(state='completed', progress=100, bytes=output.stat().st_size,
                       sha256=digest.hexdigest(), message='Résultat prêt')
        except Exception as exc:
            job.update(state='failed', error=str(exc), message=str(exc))
        finally:
            if 'working_source' in locals():
                working_source.unlink(missing_ok=True)
            self.save(job)


def handler(lab, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # Do not log authorization headers or private model names.

        def reply(self, code, body):
            data = json.dumps(body, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def authorized(self):
            valid = hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token)
            if not valid:
                self.reply(401, {'error': 'Non autorisé'})
            return valid

        def do_GET(self):
            if not self.authorized():
                return
            try:
                if self.path == '/capabilities':
                    return self.reply(200, lab.capabilities())
                match = re.fullmatch('/jobs/([a-f0-9]{32})(/artifact)?', self.path)
                if not match:
                    return self.reply(404, {'error': 'Route inconnue'})
                job = lab.status(match[1])
                if not match[2]:
                    return self.reply(200, job)
                if job['state'] != 'completed':
                    return self.reply(409, {'error': 'Résultat indisponible'})
                p = lab.root / match[1] / 'result.gguf'
                self.send_response(200)
                self.send_header('Content-Length', str(p.stat().st_size))
                self.send_header('Content-Type', 'application/octet-stream')
                self.end_headers()
                with p.open('rb') as stream:
                    import shutil
                    shutil.copyfileobj(stream, self.wfile)
            except (ValueError, FileNotFoundError):
                self.reply(404, {'error': 'Tâche inconnue'})

        def do_POST(self):
            if not self.authorized():
                return
            try:
                if self.path != '/jobs':
                    return self.reply(404, {'error': 'Route inconnue'})
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= 8192:
                    raise ValueError('Requête trop volumineuse')
                data = json.loads(self.rfile.read(size))
                self.reply(202, lab.submit(data['operation'], data['model']))
            except (ValueError, KeyError, TypeError, FileNotFoundError) as exc:
                self.reply(400, {'error': str(exc)})
    return Handler


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--jobs', default='./lexos-jobs')
    parser.add_argument('--cert', required=True)
    parser.add_argument('--key', required=True)
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8443)
    args = parser.parse_args()
    token = os.environ.get('LEXOS_LAB_TOKEN', '')
    if len(token) < 32:
        parser.error('LEXOS_LAB_TOKEN : au moins 32 caractères requis')
    lab = Lab(json.loads(Path(args.config).read_text()), args.jobs)
    server = ThreadingHTTPServer((args.host, args.port), handler(lab, token))
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(args.cert, args.key)
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
