#!/usr/bin/env bash
# Local mirror of the CI jvm-compile + jvm-test gates (identical strategy:
# kotlinc against pure-JVM deps + minimal Android stubs). Usage:
#   scripts/local_jvm_gate.sh [compile|test|all]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-all}"
KOTLINC="${HOME}/pl-kotlinc/bin/kotlinc"
LIBS="/tmp/pl-libs"
OUT="/tmp/local-out"
STUBS="/tmp/local-stubs"

[ -x "$KOTLINC" ] || { echo "kotlinc not found at $KOTLINC"; exit 1; }

materialize_stubs() {
  mkdir -p "$STUBS/android/graphics" "$STUBS/android/os"
  cat > "$STUBS/android/graphics/Bitmap.kt" <<'KSTUB'
package android.graphics
import java.io.OutputStream
class Bitmap private constructor() {
    companion object {
        @JvmStatic fun createBitmap(pixels: IntArray, width: Int, height: Int, config: Config): Bitmap = Bitmap()
        @JvmStatic fun createBitmap(width: Int, height: Int, config: Config): Bitmap = Bitmap()
        @JvmStatic fun createScaledBitmap(src: Bitmap, width: Int, height: Int, filter: Boolean): Bitmap = Bitmap()
    }
    enum class Config { ALPHA_8, RGB_565, ARGB_8888 }
    enum class CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSLESS }
    val width: Int = 1
    val height: Int = 1
    val config: Config = Config.ARGB_8888
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {}
    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {}
    fun copy(config: Config, isMutable: Boolean): Bitmap = Bitmap()
    fun recycle() {}
    fun isRecycled(): Boolean = false
    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean = true
}
KSTUB
  cat > "$STUBS/android/os/Build.kt" <<'KSTUB'
package android.os
object Build {
    object VERSION {
        const val SDK_INT: Int = 34
    }
    const val VERSION_CODES_BASE: Int = 1
    val MANUFACTURER: String = "stub"
    val MODEL: String = "stub"
}
KSTUB
}

compile_all() {
  rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/mcp" "$OUT/test" "$OUT/mcp-test"
  echo "── [1/4] pixel-core main ──"
  "$KOTLINC" -cp "$LIBS/kotlinx-coroutines-core-jvm-1.10.2.jar" -jvm-target 17 -d "$OUT/main" \
    $(find "$ROOT/pixel-core/src/main/kotlin" -name '*.kt' | sort) \
    $(find "$STUBS" -name '*.kt' | sort)
  echo "── [2/4] pixel-mcp main ──"
  "$KOTLINC" -cp "$OUT/main:$LIBS/kotlinx-coroutines-core-jvm-1.10.2.jar" -jvm-target 17 -d "$OUT/mcp" \
    $(find "$ROOT/pixel-mcp/src/main/kotlin" -name '*.kt' | sort)
  echo "── [3/4] pixel-core tests ──"
  if find "$ROOT/pixel-core/src/test/kotlin" -name '*.kt' | grep -q .; then
    "$KOTLINC" -cp "$OUT/main:$LIBS/kotlinx-coroutines-core-jvm-1.10.2.jar:$LIBS/kotlin-test-2.3.21.jar:$LIBS/junit-4.13.2.jar" \
      -jvm-target 17 -d "$OUT/test" $(find "$ROOT/pixel-core/src/test/kotlin" -name '*.kt' | sort)
  else
    echo "no core test sources"
  fi
  echo "── [4/4] pixel-mcp tests ──"
  if find "$ROOT/pixel-mcp/src/test/kotlin" -name '*.kt' | grep -q .; then
    "$KOTLINC" -cp "$OUT/main:$OUT/mcp:$LIBS/kotlinx-coroutines-core-jvm-1.10.2.jar:$LIBS/kotlin-test-2.3.21.jar:$LIBS/junit-4.13.2.jar" \
      -jvm-target 17 -d "$OUT/mcp-test" $(find "$ROOT/pixel-mcp/src/test/kotlin" -name '*.kt' | sort)
  else
    echo "no mcp test sources"
  fi
}

run_tests() {
  CP="$OUT/main:$OUT/test:$OUT/mcp:$OUT/mcp-test:$LIBS/kotlinx-coroutines-core-jvm-1.10.2.jar:$LIBS/kotlin-test-2.3.21.jar:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:$LIBS/kotlin-stdlib-2.3.21.jar"
  [ -f "$LIBS/kotlin-stdlib-2.3.21.jar" ] || curl -sL -f -o "$LIBS/kotlin-stdlib-2.3.21.jar" https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.3.21/kotlin-stdlib-2.3.21.jar
  [ -f "$LIBS/junit-platform-console-standalone-1.10.3.jar" ] || curl -sL -f -o "$LIBS/junit-platform-console-standalone-1.10.3.jar" https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.3/junit-platform-console-standalone-1.10.3.jar
  java -jar "$LIBS/junit-platform-console-standalone-1.10.3.jar" \
    --class-path "$CP" --scan-class-path --details tree --fail-if-no-tests \
    | tail -40
}

materialize_stubs
case "$MODE" in
  compile) compile_all ;;
  test) run_tests ;;
  all) compile_all; run_tests ;;
  *) echo "usage: $0 [compile|test|all]"; exit 1 ;;
esac
echo "✅ local JVM gate: $MODE passed"
