# Heretic Mobile — Atelier IA local

Version 0.3.0. Application Android Kotlin pour utiliser et personnaliser des modèles GGUF sur l’appareil. Interface française noire, texte blanc et boutons bordés d’orange néon. Le chat local fonctionne hors ligne, sans compte ni serveur requis. Aucun modèle n’est livré dans l’APK ou ajouté au dépôt.

## Utiliser l’atelier

Android 8.0 / API 26 minimum, arm64-v8a (Samsung S25 Ultra) ou x86_64. Installer l’APK debug de GitHub Actions.

1. **Trouver mes IA sur cet appareil** : autoriser des dossiers dans le sélecteur Android, puis rechercher leurs GGUF et sous-dossiers. Les autorisations sont mémorisées pour Documents, dossiers personnels, sous-dossiers de Téléchargements et clés USB accessibles à Android. **Actualiser** recherche dans tous les dossiers mémorisés ; **Ouvrir un fichier GGUF** ouvre un document individuel.
2. Sélectionner un modèle. Une lecture directe est tentée sans copie ; certains fournisseurs nécessitent une copie privée. Nom, taille, emplacement et état connu de compatibilité sont affichés. Les modèles privés d’une autre application nécessitent une exportation par cette application. Android peut refuser la racine Téléchargements : choisir un sous-dossier ou ouvrir le fichier.
3. Dans **Options**, ajuster les réglages simples ou avancés, la personnalité ou les profils. Envoyer un message ; **Arrêter** interrompt le chargement ou le calcul quand le moteur rend la main. Les mesures affichent les tokens/s et le délai du premier texte. **Décharger** libère le modèle de la RAM en conservant sa sélection.
4. **Comparer des configurations** lance le même prompt successivement avec plusieurs profils sur le modèle actif. Les 100 derniers résultats sont conservés séparément des conversations.

Les modèles ne sont jamais déplacés ou supprimés automatiquement. Les anciennes copies privées restent sélectionnables. Les actions manuelles de suppression et déplacement des sources demandent confirmation. Les conversations et réglages existants sont conservés dans leurs espaces de stockage historiques ; les profils et comparaisons utilisent de nouveaux fichiers atomiques. Une rotation conserve la génération via le ViewModel ; Android tuant le processus peut perdre la réponse en cours. Les sauvegardes Android restent désactivées. La désinstallation efface les données privées de l’application.

## Personnalisation prise en charge

- Température, top-p, top-k, min-p, pénalité et fenêtre de répétition, graine, longueur de réponse, contexte et threads CPU sont reliés au moteur natif.
- Six profils initiaux : code, discussion, analyse, création, rapide et économie de batterie. Créer, remplacer, dupliquer, exporter et importer des profils avec leurs réglages et personnalités ; plusieurs configurations peuvent être associées à un modèle.
- Consignes système, langue, ton et spécialité modifiables. Ce sont des paramètres d’inférence et du texte système : ils ne changent pas les poids.
- **Créer une copie quantifiée** produit un nouveau GGUF Q4_K_M, Q5_K_M ou Q8_0 sans remplacer l’original. Cette conversion numérique des poids peut dégrader la qualité, exige RAM et stockage, et reste non interruptible. Importer/sélectionner le résultat pour l’utiliser.
- **LexOS distant**, facultatif : connecter le téléphone au compagnon HTTPS du PC pour les opérations lourdes. Les tâches, progression et résultats sont récupérables. LoRA, entraînement ou ablation ne sont proposés comme opérations exécutables que si une recette correspondante est réellement installée sur le PC. Aucun entraînement Android ni chargement d’adaptateur LoRA externe n’est intégré.

[Guide complet de l’atelier, des limites et du compagnon PC](docs/MOBILE_AI_LAB.md).

## Moteur et compatibilité

llama.cpp CPU via JNI, version b5046 / `74d4f5b041ad837153b0e90fc864b8290e01d8d5`, archive vérifiée par SHA-256 à la compilation. Aucun téléchargement de moteur à l’exécution. Pas d’accélération GPU.

GGUF est un conteneur : l’extension ne garantit pas la compatibilité de l’architecture. Les informations du modèle vérifient ses métadonnées et son vocabulaire ; seule la génération vérifie le chargement complet et la disponibilité de RAM. Formats apparus après b5046, multiparties et adaptateurs externes peuvent être incompatibles. Un échec de chargement indique l’incompatibilité ou un manque de mémoire sans prétendre distinguer ces causes si le moteur ne le permet pas.

Contexte par défaut : 2 048 tokens ; réponse : 512 ; threads : 4 ; température : 0,7 ; top-p : 0,9 ; top-k : 40. Les limites et explications de chaque réglage sont affichées dans l’interface. La réponse maximale est réservée dans le contexte ; l’historique n’est pas tronqué silencieusement. Le format de chat déclaré du GGUF est respecté ; ChatML sert de repli s’il est absent. Le modèle reste chargé entre les messages, avec un nouveau contexte de calcul à chaque tour.

## Compilation et tests

JDK 17 ou 21, SDK Android 35, NDK 27.2.12479018 et CMake 3.22.1. Le premier build télécharge Gradle, les dépendances et llama.cpp.

```sh
# ANDROID_HOME ou local.properties doit pointer vers le SDK.
./gradlew :core:test :data:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=2
python3 -m unittest discover -s pc -v
```

APK : `app/build/outputs/apk/debug/app-debug.apk`. Le workflow **Android APK** publie `Heretic-Mobile-debug` seulement après réussite des tests, du lint et de la compilation. Il produit également les rapports. L’APK est signé avec une clé de développement ; une mise à jour conserve les données seulement si sa signature est compatible avec l’installation existante. Ne pas désinstaller l’ancienne application pour contourner une incompatibilité sans avoir sauvegardé ses données.

Le workflow **Native JNI** exécute le pont C++ de production sur Linux avec un petit modèle public téléchargé dans le répertoire temporaire du runner. Il vérifie streaming, arrêt, réglages invalides, graine déterministe, informations du modèle, dépassement de contexte, quantifications Q4/Q5/Q8 et source inchangée. Cela ne remplace pas un essai Android.

Les tests JVM couvrent les réglages/personnalité, la migration et sérialisation JSON des réglages, le flux UTF-8 et la découverte récursive avec annulation, cycles et limites. Les tests PC couvrent capacités, recettes refusées, progression, résultats, source préservée, échec et redémarrage.

À tester physiquement : mise à jour et conservation des données sur S25 Ultra, SAF et USB, lecture directe et repli vers copie, permissions après redémarrage/révocation, clavier et rotation, modèles personnels et pression mémoire, chauffe/autonomie et mesures, arrêt pendant chargement, quantification longue, profils import/export, comparaisons et réseau HTTPS téléphone-PC. Les entraînements/LoRA/ablations réels nécessitent les outils et données du PC et ne sont pas validés par les tests de protocole. La liste détaillée figure dans le guide.

## Structure et licences

`app` : interface/ViewModel ; `core` : contrats, validation et découverte ; `data` : SAF, stockage et protocole PC ; `inference` : JNI/CMake/llama.cpp ; `pc` : compagnon facultatif. Le module historique `websearch` reste conservé.

llama.cpp est sous licence MIT : voir [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) et `licenses/llama.cpp-LICENSE`. AndroidX et Kotlin conservent leurs licences. Aucune licence globale du présent dépôt n’est présumée.
