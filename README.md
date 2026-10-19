# Heretic-Mobile

Application Android Kotlin autonome de chat avec un modèle GGUF **sur l’appareil**. Fond noir, texte blanc, boutons et options orange, interface française. Aucun serveur, compte ou composant CodexLocal. Aucune fonction de modification des protections des modèles.

## Utilisation

Android 8.0 (API 26) minimum, appareil **arm64-v8a** (ou émulateur x86_64).

1. Installer l’APK debug construit par Gradle ou disponible dans les artefacts d’une exécution GitHub Actions réussie.
2. Appuyer sur **Modèle GGUF** et choisir un fichier `.gguf` dans le sélecteur de documents Android (SAF).
3. L’application copie le modèle dans son stockage privé. Prévoir de l’espace pour cette copie en plus du fichier source. Un nouvel import remplace la copie précédente seulement après sa réussite.
4. Écrire puis appuyer sur **Envoyer**. Le texte apparaît progressivement. **Arrêter** interrompt le chargement ou le calcul dès que le moteur rend la main. Une lecture lente d’un fournisseur SAF peut retarder l’arrêt d’une importation.
5. **Nouvelle**, **Historique** et **Supprimer** permettent de gérer les discussions locales. Les réponses partielles sont conservées après un arrêt normal. La suppression demande confirmation.

Les messages sont enregistrés avant et après chaque génération, dans un fichier JSON atomique. La dernière réponse en cours peut être perdue si Android tue le processus. La sélection du modèle et l’historique survivent aux redémarrages ; une rotation garde la génération grâce au ViewModel. Quitter définitivement l’activité arrête le moteur. Les sauvegardes Android sont désactivées. La désinstallation efface les modèles importés et l’historique. Aucun accès réseau ni permission générale de stockage n’est demandé par l’application.

## Moteur et limites

llama.cpp CPU intégré par JNI, version **b5046 / 74d4f5b041ad837153b0e90fc864b8290e01d8d5**, archive vérifiée par SHA-256 à la compilation. Le GGUF doit contenir une architecture prise en charge par cette version de llama.cpp et tenir dans la RAM disponible. GGUF est un conteneur : l’extension seule ne garantit pas la compatibilité. Les formats apparus après cette version peuvent échouer. Les modèles multiparties et les adaptateurs externes ne sont pas pris en charge.

Contexte fixé à **2 048 tokens**, dont **512 maximum pour la réponse**, 4 threads CPU, température 0,7 et top-p 0,9. L’historique complet de la discussion est transmis au moteur ; si le contexte est dépassé, l’application demande une nouvelle discussion. Le modèle conserve son format de chat déclaré dans le GGUF ; ChatML est utilisé si aucun format n’est présent. Un format déclaré inconnu produit une erreur explicite. Aucun message système destiné à supprimer les protections n’est injecté.

Le modèle chargé reste en mémoire pour les messages suivants ; un contexte de calcul neuf est créé à chaque tour. Aucun modèle n’est fourni dans l’APK. Vitesse, chauffe et mémoire dépendent de l’appareil et du modèle. Cette première version utilise le CPU, sans accélération GPU.

## Structure modifiable

| Module | Responsabilité |
| --- | --- |
| `app` | Écran français, thème, ViewModel et orchestration du chat |
| `core` | Messages, conversations, contrat d’inférence et décodeur UTF-8 incrémental |
| `data` | Import SAF, sélection du modèle, historique JSON atomique |
| `inference` | Implémentation llama.cpp, JNI, arrêt atomique et CMake |

Les couleurs se trouvent dans `MainActivity.kt` et `res/values/styles.xml`. Les paramètres de génération sont dans `inference/src/main/cpp/heretic.cpp`. Le contrat `InferenceEngine` permet de remplacer le moteur.

## Compilation

Prérequis : JDK 17 ou 21, SDK Android 35, NDK 27.2.12479018, CMake 3.22.1. Internet est nécessaire au premier build pour télécharger Gradle, les dépendances et l’archive llama.cpp. Aucun téléchargement de moteur ne se produit à l’exécution sur le téléphone.

```sh
# Définir ANDROID_HOME ou créer local.properties avec sdk.dir=/chemin/vers/sdk
./gradlew :core:test :app:lintDebug :app:assembleDebug --max-workers=2
```

Sortie : `app/build/outputs/apk/debug/app-debug.apk`. APK de développement signé avec une clé debug, pas une version de distribution. Le workflow `.github/workflows/android.yml` compile sur chaque push sur main et ne publie l’APK que si le build réussit. Consulter l’exécution et son commit pour connaître l’état réel ; la présence de ce workflow ne prouve pas qu’un build a réussi.

Les tests JVM vérifient le streaming UTF-8, notamment les accents et emojis coupés entre tokens. Une validation sur téléphone reste nécessaire pour l’import SAF avec ses fournisseurs, la pression mémoire, le clavier et l’inférence avec le GGUF choisi.

## Dépendances

llama.cpp est distribué sous licence MIT, voir `THIRD_PARTY_NOTICES.md` et `licenses/llama.cpp-LICENSE`. L’archive contient également ses notices tierces. AndroidX et Kotlin gardent leurs licences respectives. Aucune licence globale du présent dépôt n’est présumée.
