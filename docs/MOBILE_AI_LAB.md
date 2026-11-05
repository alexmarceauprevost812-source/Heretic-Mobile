# Atelier mobile 0.3

## Fonctions

L’atelier conserve l’identifiant Android `ca.heretic.mobile`, les discussions et les clés historiques de réglages. Les nouveaux fichiers de profils et de comparaisons sont séparés. Les copies privées de modèles déjà importées ne sont plus effacées lors d’un nouvel import et peuvent être sélectionnées depuis la liste des copies conservées. Aucun modèle n’est ajouté au dépôt.

Options propose les réglages simples, avancés, la personnalité, les profils, les comparaisons, les informations GGUF, le déchargement et les tâches PC. Les six profils initiaux sont des points de départ ; le profil batterie réduit threads et longueur, sans promettre une autonomie mesurée.

| Option | Moteur llama.cpp b5046 |
|---|---|
| Température | `llama_sampler_init_temp` |
| Top-k / Top-p / Min-p | filtres `top_k`, `top_p`, `min_p` |
| Répétitions | `llama_sampler_init_penalties`, fenêtre et multiplicateur ; fréquence et présence restent à zéro |
| Graine | `llama_sampler_init_dist`, -1 utilise `LLAMA_DEFAULT_SEED` |
| Réponse | limite de la boucle native, réservée dans le contexte |
| Contexte | `llama_context_params.n_ctx`, contexte neuf par génération |
| Threads | `n_threads` et `n_threads_batch` |

Les consignes, langue, ton et spécialité forment un message système. Cela ne modifie pas les poids. Plusieurs profils peuvent porter le même modèle associé ; appliquer un profil conserve le modèle sélectionné et les messages. Créer capture les réglages actuels ; remplacer met à jour un profil ; dupliquer conserve les réglages du profil source. L’import JSON ajoute les profils sans remplacer ceux déjà présents. Une version inconnue ou des valeurs hors limites sont refusées.

Les comparaisons exécutent successivement les profils choisis sur le même modèle actif, avec le même prompt et un historique neuf. Les 100 derniers résultats (réponse, réglages, erreur éventuelle, arrêt et mesures) sont enregistrés dans un JSON atomique. La réponse partielle d’une comparaison interrompue normalement est conservée ; Android tuant le processus peut perdre le résultat en cours.

Les tokens/s comptent les tokens natifs émis, pas les mots ou les caractères. Le débit exclut le chargement et le préremplissage et mesure les intervalles après le premier token. Le délai du premier texte utilise une horloge monotone depuis l’entrée dans la génération, chargement et préremplissage inclus. Une sortie vide n’a pas de délai de premier texte. Décharger libère le modèle natif sans effacer la sélection.

## Trouver les modèles

**Trouver mes IA sur cet appareil** ouvre la gestion SAF. **Choisir dossier** ajoute une autorisation persistante ; **Actualiser** explore tous les dossiers mémorisés et leurs sous-dossiers, avec limites, détection des cycles et arrêt. Un dossier indisponible (clé USB retirée, autorisation révoquée) produit un avertissement sans masquer les autres résultats. **Ouvrir un fichier GGUF** sélectionne un document individuel.

Le téléphone ne donne pas accès au stockage privé d’une autre application. Exporter le GGUF depuis cette application vers Documents ou un autre dossier partagé. Android 11+ peut refuser la racine Téléchargements : utiliser un sous-dossier ou ouvrir le fichier individuellement. Les clés USB dépendent du fournisseur Documents Android et de leur présence.

Un descripteur SAF de fichier régulier et repositionnable est conservé ouvert et transmis au moteur par `/proc/self/fd`. Le modèle est alors utilisé sans copie et l’autorisation de lecture est mémorisée. Un fournisseur virtuel, non repositionnable ou sans droit persistant déclenche une copie privée. Le refus de lecture d’un document déjà sélectionné est une erreur explicite : le choisir à nouveau après avoir rétabli son accès. Le mode local ne fait aucun appel réseau ; un fournisseur de fichiers cloud peut toutefois télécharger lui-même le document sélectionné.

Le nom, la taille et le chemin du dossier autorisé sont affichés. Avant sélection, la compatibilité est inconnue. **Informations et compatibilité** charge uniquement les métadonnées et le vocabulaire : une reconnaissance par b5046 ne prouve pas la capacité à charger les tenseurs ni la disponibilité de RAM. La génération vérifie le chargement complet. Multiparties, adaptateurs externes et architectures absentes de b5046 ne sont pas pris en charge.

La quantification Q4_K_M/Q5_K_M/Q8_0 crée un nouveau fichier ; l’original reste inchangé. Elle peut modifier les poids numériques et dégrader la qualité. Elle n’est pas un entraînement. Son appel natif reste non interruptible. L’import, la sélection et la découverte ne déplacent ni ne suppriment de modèles. Les actions manuelles existantes de déplacement/suppression restent explicites et demandent confirmation.

## LexOS PC facultatif

Le protocole de `pc/lexos_lab.py` est un nouveau compagnon à installer sur le PC ; il ne présume pas qu’une installation LexOS existante expose cette API. Python 3.10+ et les outils de calcul sont requis. Le serveur n’installe aucun outil ni modèle. Sa configuration locale définit les modèles et les recettes argv autorisées. Sans recette installée, LoRA, entraînement ou ablation sont refusés et ne sont jamais simulés avec une consigne.

Exemple de configuration **sur le PC**, avec chemins absolus :

```json
{
  "models": {"mon-modele": "/chemin/PC/modele.gguf"},
  "recipes": {
    "quantize": ["/chemin/llama-quantize", "{input}", "{output}", "Q4_K_M"]
  }
}
```

Pour `lora`, `train`, `ablate`, ajouter uniquement une commande réellement installée et vérifiée sur le PC, sous forme de liste d’arguments, jamais de commande shell fournie par le téléphone. Le programme appelé doit accepter les substitutions `{input}` et `{output}`, produire un fichier résultat et peut émettre `{"progress": 42, "message": "étape"}` sur stdout. Sans pourcentage fourni par l’outil, la progression reste indéterminée et le dernier message est disponible. Un entraînement nécessitant un dossier Safetensors ou un dataset exige un wrapper PC adapté ; un GGUF ne devient pas entraînable par ce protocole. Un adaptateur LoRA seul ne peut pas être chargé par le moteur Android actuel : fusionner/exporter en GGUF avec les outils PC appropriés.

```sh
export LEXOS_LAB_TOKEN='un-secret-aleatoire-de-32-caracteres-ou-plus'
python3 pc/lexos_lab.py --config /chemin/config.json --jobs /chemin/resultats \
  --cert /chemin/certificat.pem --key /chemin/cle.pem
```

Le certificat HTTPS doit être approuvé par Android (aucun contournement TLS). Utiliser un certificat reconnu pour le nom DNS du PC. Le jeton du téléphone reste en mémoire ; le saisir à nouveau après redémarrage. L’adresse et le dernier identifiant de tâche sont mémorisés. Aucune donnée personnelle du téléphone n’est envoyée : seulement l’opération et l’identifiant de modèle PC. Les commandes sont exécutées successivement sur une copie de travail PC ; le résultat est distinct de l’original. Prévoir l’espace pour cette copie. Les chemins absolus codés dans une recette restent sous la responsabilité de sa configuration sur le PC.

**Connecter** affiche capacités et modèles connus ; lancer une tâche, puis **Actualiser la progression** et **Récupérer le résultat**. Une tâche survit à la fermeture du téléphone. Un redémarrage du compagnon marque les tâches actives interrompues ; les résultats terminés restent récupérables. Les résultats PC ne sont pas supprimés automatiquement. La récupération écrit au document explicitement choisi ; en cas d’interruption réseau, réessayer sur un nouveau document. Le SHA-256 est fourni dans l’état de la tâche, mais sa vérification après transfert n’est pas encore automatisée dans le téléphone.

## Validation et limites téléphone

Le workflow Android compile l’APK debug après tests JVM et lint ; le workflow natif lance le pont JNI Linux sur un petit modèle public de test, hors dépôt. Le compagnon possède des tests sur les recettes refusées, progression/résultat, source préservée, échec et redémarrage.

À valider sur Samsung S25 Ultra : installation par-dessus l’ancienne version (signature debug identique requise), préservation réelle des données, fournisseurs SAF Documents/Téléchargements/USB, lecture `/proc/self/fd`, repli vers copie, autorisations après redémarrage/révocation, retraits USB, clavier/rotation, GGUF personnels et architecture, RAM insuffisante et mort du processus, chauffe/autonomie, précision temporelle, arrêt pendant chargement/préremplissage, quantification longue et espace disque, profils import/export, comparaison interrompue, HTTPS téléphone-PC et récupération volumineuse. Les recettes réelles d’entraînement/LoRA/ablation n’ont pas été exécutées sans votre PC et vos outils.
