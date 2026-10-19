# Heretic-Mobile

Version 0.2.0 — Application Android Kotlin autonome de chat avec un modèle GGUF **sur l’appareil**. Fond noir, texte blanc, boutons et options orange, interface française. Aucun serveur, compte ou composant CodexLocal. Aucune fonction de modification des protections des modèles.

## Utilisation

Android 8.0 (API 26) minimum, appareil **arm64-v8a** (ou émulateur x86_64).

1. Installer l’APK debug construit par Gradle ou disponible dans les artefacts d’une exécution GitHub Actions réussie.
2. Appuyer sur **Fichier GGUF** pour ouvrir un fichier directement, ou sur **IA locales → Choisir dossier** pour rechercher les `.gguf` déjà téléchargés dans un dossier et ses sous-dossiers. Appuyer sur un résultat, puis **Lire / utiliser dans le chat**.
3. L’application copie le modèle dans son stockage privé. Prévoir de l’espace pour cette copie en plus du fichier source. Un nouvel import remplace la copie précédente seulement après sa réussite.
4. Écrire puis appuyer sur **Envoyer**. Le texte apparaît progressivement. **Arrêter** interrompt le chargement ou le calcul dès que le moteur rend la main. Une lecture lente d’un fournisseur SAF peut retarder l’arrêt d’une importation.
5. **Nouvelle**, **Historique** et **Supprimer** permettent de gérer les discussions locales. Les réponses partielles sont conservées après un arrêt normal. La suppression demande confirmation.

Les messages sont enregistrés avant et après chaque génération, dans un fichier JSON atomique. La dernière réponse en cours peut être perdue si Android tue le processus. La sélection du modèle et l’historique survivent aux redémarrages ; une rotation garde la génération grâce au ViewModel. Quitter définitivement l’activité arrête le moteur. Les sauvegardes Android sont désactivées. La désinstallation efface les modèles importés et l’historique. Aucun accès réseau ni permission générale de stockage n’est demandé par l’application.

## Voir et gérer les IA déjà téléchargées

**IA locales** affiche les noms, tailles et sous-dossiers des GGUF du dossier choisi. L’accès au dossier est mémorisé ; **Actualiser** relance la recherche, notamment après un redémarrage. Un dossier vide n’est pas présenté comme une absence de modèles sur tout l’appareil. La recherche est limitée à 10 000 entrées et peut être arrêtée avec **Arrêter** ; un fournisseur Android bloquant peut retarder cet arrêt.

Chaque résultat propose **Lire / utiliser**, **Renommer**, **Déplacer** et **Supprimer**. Ces trois dernières actions modifient le fichier source du dossier autorisé, selon les capacités du fournisseur. Déplacer et supprimer demandent confirmation. Le déplacement direct exige le même fournisseur de documents. La copie du modèle déjà importée dans Heretic-Mobile reste inchangée ; pour utiliser un fichier modifié, l’importer à nouveau. Renommer un fichier ne modifie pas ses poids.

Sur Android 11 et supérieur, les racines de stockage, le dossier Téléchargements lui-même, Android/data et Android/obb peuvent être interdits au sélecteur de dossiers. Utiliser un sous-dossier (par exemple Documents/Modeles) ou **Fichier GGUF** pour un téléchargement individuel. Les fichiers privés d’autres applications ne sont pas accessibles : exporter leur GGUF vers un dossier partagé s’ils le permettent. L’application ne demande pas l’accès général à tous les fichiers et ne détecte pas les modèles au format Safetensors, ONNX ou TFLite.

Référence : https://developer.android.com/training/data-storage/shared/documents-files

## Modifier les réglages et convertir les poids

**Options → Réglages du chat** permet de mémoriser température (0–2), top-p (0,05–1), réponse maximale (16–1 024 tokens), threads CPU (1–8) et une consigne de conversation facultative. Aucun texte n’est injecté par défaut. Ces réglages contrôlent l’inférence ; ils ne réentraînent pas le modèle.

**Options → Convertir le modèle actif** crée un nouveau GGUF en **Q4_K_M**, **Q5_K_M** ou **Q8_0**, puis l’exporte vers le fichier choisi via Android SAF. L’original et la copie active sont conservés. Importer le résultat pour l’utiliser. Une requantification peut dégrader la qualité ; Q8 peut produire un fichier plus volumineux. Le modèle source doit être pris en charge par le moteur et certaines quantifications d’entrée peuvent être refusées. La conversion utilise une copie temporaire privée ; une réserve de quatre fois la taille du modèle plus 64 Mio est demandée. Elle nécessite de la RAM et peut échouer ou être arrêtée par Android.

La conversion native ne dispose pas d’arrêt coopératif : le bouton Arrêter est désactivé pendant cette opération et son export. Elle peut durer longtemps sur téléphone. Les fichiers temporaires sont supprimés à la fin ; après un échec d’export, une destination partielle peut subsister si le fournisseur refuse sa suppression. Un arrêt forcé du processus peut également laisser un temporaire dans le cache Android.

La conversion ici concerne **GGUF vers GGUF** : aucun entraînement complet, conversion Safetensors ou mécanisme de désactivation des protections des modèles n’est fourni.

## Moteur et limites

llama.cpp CPU intégré par JNI, version **b5046 / 74d4f5b041ad837153b0e90fc864b8290e01d8d5**, archive vérifiée par SHA-256 à la compilation. Le GGUF doit contenir une architecture prise en charge par cette version de llama.cpp et tenir dans la RAM disponible. GGUF est un conteneur : l’extension seule ne garantit pas la compatibilité. Les formats apparus après cette version peuvent échouer. Les modèles multiparties et les adaptateurs externes ne sont pas pris en charge.

Contexte fixé à **2 048 tokens**. Valeurs par défaut : réponse maximale de 512 tokens, 4 threads CPU, température 0,7 et top-p 0,9, modifiables dans Options. Le moteur réserve dans le contexte la longueur maximale de réponse choisie. L’historique complet de la discussion est transmis au moteur ; si le contexte est dépassé, l’application demande une nouvelle discussion. Le modèle conserve son format de chat déclaré dans le GGUF ; ChatML est utilisé si aucun format n’est présent. Un format déclaré inconnu produit une erreur explicite. Aucun message système destiné à supprimer les protections n’est injecté.

Le modèle chargé reste en mémoire pour les messages suivants ; un contexte de calcul neuf est créé à chaque tour. Aucun modèle n’est fourni dans l’APK. Vitesse, chauffe et mémoire dépendent de l’appareil et du modèle. Cette première version utilise le CPU, sans accélération GPU.

## Structure modifiable

| Module | Responsabilité |
| --- | --- |
| `app` | Écran français, thème, ViewModel et orchestration du chat |
| `core` | Messages, paramètres validés, découverte récursive, contrat d’inférence et décodeur UTF-8 incrémental |
| `data` | Import SAF, catalogue et gestion des fichiers GGUF, réglages et historique JSON atomique |
| `inference` | Inférence et quantification llama.cpp, JNI, arrêt atomique du chat et CMake |

Les couleurs se trouvent dans `MainActivity.kt` et `res/values/styles.xml`. Les paramètres de génération sont dans `inference/src/main/cpp/heretic.cpp`. Le contrat `InferenceEngine` permet de remplacer le moteur.

## Compilation

Prérequis : JDK 17 ou 21, SDK Android 35, NDK 27.2.12479018, CMake 3.22.1. Internet est nécessaire au premier build pour télécharger Gradle, les dépendances et l’archive llama.cpp. Aucun téléchargement de moteur ne se produit à l’exécution sur le téléphone.

```sh
# Définir ANDROID_HOME ou créer local.properties avec sdk.dir=/chemin/vers/sdk
./gradlew :core:test :app:lintDebug :app:assembleDebug --max-workers=2
```

Sortie : `app/build/outputs/apk/debug/app-debug.apk`. APK de développement signé avec une clé debug, pas une version de distribution. Le workflow `.github/workflows/android.yml` compile sur chaque push sur main et ne publie l’APK que si le build réussit. Consulter l’exécution et son commit pour connaître l’état réel ; la présence de ce workflow ne prouve pas qu’un build a réussi.

Les tests JVM vérifient le streaming UTF-8, la recherche récursive avec cycles/doublons/annulation/limites, ainsi que les paramètres de génération et la consigne facultative. Le test JNI Linux vérifie aussi les conversions Q4/Q5/Q8 sur un petit modèle réel et l’inférence avec chaque résultat, sans modification du modèle source. Une validation sur téléphone reste nécessaire pour l’import SAF avec ses fournisseurs, la pression mémoire, le clavier et l’inférence avec le GGUF choisi.

## Dépendances

llama.cpp est distribué sous licence MIT, voir `THIRD_PARTY_NOTICES.md` et `licenses/llama.cpp-LICENSE`. L’archive contient également ses notices tierces. AndroidX et Kotlin gardent leurs licences respectives. Aucune licence globale du présent dépôt n’est présumée.
