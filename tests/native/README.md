# Test JNI sur ordinateur Linux

Ce test charge un petit GGUF réel et utilise le fichier C++ de production via JNI : réception de tokens, arrêt après 5 tokens, refus d’un contexte trop long et erreur de modèle absent. Il ne valide ni l’interface Android ni la qualité conversationnelle du modèle. Le modèle TinyStories de test n’est pas un modèle de chat et n’est pas livré dans l’APK.

Compiler llama.cpp au commit du CMake avec BUILD_SHARED_LIBS=OFF, CMAKE_POSITION_INDEPENDENT_CODE=ON et LLAMA_CURL=OFF. Lier `heretic.cpp` avec libllama.a, libggml.a, libggml-cpu.a et libggml-base.a, les includes JNI du JDK et pthread/dl ; produire libheretic.so. Compiler ce fichier Java avec `javac -d classes LlamaEngine.java` et lancer :

```sh
java -Djava.library.path=/chemin/des/bibliotheques -cp classes ca.heretic.inference.LlamaEngine /chemin/du/modele.gguf
```

Le script `run.sh` automatise ces étapes sans télécharger de modèle :

```sh
JAVA_HOME=/chemin/du/jdk ./tests/native/run.sh /chemin/llama.cpp /chemin/modele.gguf /chemin/dossier-temporaire
```

Les sources llama.cpp doivent correspondre au commit figé dans le CMake de production.
