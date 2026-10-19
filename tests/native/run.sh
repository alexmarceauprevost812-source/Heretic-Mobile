#!/usr/bin/env bash
set -euo pipefail
# Usage: JAVA_HOME=/path/to/jdk ./run.sh /path/to/llama.cpp /path/to/model.gguf /path/to/scratch
source_dir="$(cd "${1:?llama.cpp source required}" && pwd)"
model_file="$(realpath "${2:?GGUF required}")"
mkdir -p "${3:?scratch directory required}"
scratch_dir="$(cd "$3" && pwd)"
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
: "${JAVA_HOME:?A complete JDK is required}"
cmake -S "$source_dir" -B "$scratch_dir/llama" -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF
cmake --build "$scratch_dir/llama" --target llama -j 2
g++ -std=c++17 -shared -fPIC -O2 -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -I"$source_dir/include" -I"$source_dir/ggml/include" "$repo_dir/inference/src/main/cpp/heretic.cpp" \
  -Wl,--start-group "$scratch_dir/llama/src/libllama.a" "$scratch_dir/llama/ggml/src/libggml.a" \
  "$scratch_dir/llama/ggml/src/libggml-cpu.a" "$scratch_dir/llama/ggml/src/libggml-base.a" \
  -Wl,--end-group -lpthread -ldl -o "$scratch_dir/libheretic.so"
"$JAVA_HOME/bin/javac" -d "$scratch_dir/classes" "$repo_dir/tests/native/LlamaEngine.java"
"$JAVA_HOME/bin/java" -Djava.library.path="$scratch_dir" -cp "$scratch_dir/classes" \
  ca.heretic.inference.LlamaEngine "$model_file"
