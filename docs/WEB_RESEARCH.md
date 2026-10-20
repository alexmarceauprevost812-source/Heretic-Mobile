# Recherche Web — chantier

Objectif : recherche automatique, analyse des pages et liens des sources, sans toucher à CodexLocal.

## État
Module Kotlin `websearch` ajouté comme base isolée. **Non fonctionnel de bout en bout** :
la réponse SearXNG n'est pas encore analysée en résultats, l'extraction HTML des pages
et l'intégration au chat sont à développer. Le module ne doit pas annoncer de
sources ou de succès tant que ces étapes ne sont pas validées.

## Architecture cible
1. Choix explicite d'une instance SearXNG HTTPS qui autorise les réponses JSON.
2. Recherche automatique uniquement sur questions à actualité évidente, avec contrôle manuel.
3. Parser JSON robuste, validation des URL HTTPS, limitation de taille et délais.
4. Téléchargement prudent du contenu HTML et extraction du texte (pas d'exécution JavaScript).
5. Injection des extraits comme données non fiables dans le prompt local.
6. Affichage des liens sources cliquables, erreurs et possibilité d'arrêter.
7. Tests sur Android réel et compilation APK.

Les requêtes Web sont envoyées au fournisseur choisi : prévenir l'utilisateur.
Aucune clé ni instance publique n'est codée en dur.
