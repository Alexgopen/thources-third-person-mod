#!/usr/bin/env bash
set -Eeuo pipefail

readonly MOD_NAME="thources-third-person-mod"
readonly PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
WURM_LAUNCHER_DIR="${WURM_LAUNCHER_DIR:-/home/alex/.local/share/Steam/steamapps/common/Wurm Unlimited/WurmLauncher}"
REPLACE_PROPERTIES=false

usage() {
    cat <<'EOF'
Usage: ./install.sh [--replace-properties]

Builds and installs Thource's Third Person mod, then runs the Wurm client patcher.

Environment:
  WURM_LAUNCHER_DIR  Wurm Unlimited WurmLauncher directory

Options:
  --replace-properties  Replace the installed mod properties file from src/dist.
                        The existing file is backed up first.
  -h, --help            Show this help.
EOF
}

while (($#)); do
    case "$1" in
        --replace-properties)
            REPLACE_PROPERTIES=true
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown option: %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

readonly MODS_DIR="$WURM_LAUNCHER_DIR/mods"
readonly MOD_DIR="$MODS_DIR/$MOD_NAME"
readonly INSTALLED_JAR="$MOD_DIR/$MOD_NAME.jar"
readonly SOURCE_PROPERTIES="$PROJECT_ROOT/src/dist/$MOD_NAME.properties"
readonly INSTALLED_PROPERTIES="$MODS_DIR/$MOD_NAME.properties"
readonly PATCHER="$WURM_LAUNCHER_DIR/patcher.sh"

if ! command -v mvn >/dev/null 2>&1; then
    printf 'Maven (mvn) is required but was not found on PATH.\n' >&2
    exit 1
fi

if [[ ! -d "$MODS_DIR" ]]; then
    printf 'Wurm mods directory not found: %s\n' "$MODS_DIR" >&2
    exit 1
fi
if [[ ! -f "$PATCHER" || ! -f "$WURM_LAUNCHER_DIR/patcher.jar" || ! -f "$WURM_LAUNCHER_DIR/javassist.jar" ]]; then
    printf 'Wurm patcher files are missing from: %s\n' "$WURM_LAUNCHER_DIR" >&2
    exit 1
fi
if [[ ! -f "$SOURCE_PROPERTIES" ]]; then
    printf 'Mod properties template not found: %s\n' "$SOURCE_PROPERTIES" >&2
    exit 1
fi

printf 'Building %s with Maven...\n' "$MOD_NAME"
cd -- "$PROJECT_ROOT"
mvn clean install

shopt -s nullglob
BUILT_JARS=("$PROJECT_ROOT/target/$MOD_NAME-"*.jar)
shopt -u nullglob
if ((${#BUILT_JARS[@]} != 1)); then
    printf 'Expected one built %s-*.jar, found %d.\n' "$MOD_NAME" "${#BUILT_JARS[@]}" >&2
    exit 1
fi
BUILT_JAR="${BUILT_JARS[0]}"

mkdir -p -- "$MOD_DIR"
TEMP_JAR="$MOD_DIR/.$MOD_NAME.jar.$$"
TEMP_PROPERTIES=""
cleanup() {
    if [[ -n "${TEMP_JAR:-}" && -e "$TEMP_JAR" ]]; then
        rm -f -- "$TEMP_JAR"
    fi
    if [[ -n "${TEMP_PROPERTIES:-}" && -e "$TEMP_PROPERTIES" ]]; then
        rm -f -- "$TEMP_PROPERTIES"
    fi
}
trap cleanup EXIT

install -m 0644 -- "$BUILT_JAR" "$TEMP_JAR"
mv -f -- "$TEMP_JAR" "$INSTALLED_JAR"
TEMP_JAR=""
printf 'Installed %s\n' "$INSTALLED_JAR"

if [[ ! -e "$INSTALLED_PROPERTIES" ]]; then
    install -m 0644 -- "$SOURCE_PROPERTIES" "$INSTALLED_PROPERTIES"
    printf 'Installed mod properties from the project template.\n'
elif cmp -s -- "$SOURCE_PROPERTIES" "$INSTALLED_PROPERTIES"; then
    printf 'Mod properties already match the project template.\n'
elif [[ "$REPLACE_PROPERTIES" == true ]]; then
    BACKUP_PROPERTIES="$INSTALLED_PROPERTIES.bak.$(date +%Y%m%d-%H%M%S).$$"
    cp -p -- "$INSTALLED_PROPERTIES" "$BACKUP_PROPERTIES"
    TEMP_PROPERTIES="$INSTALLED_PROPERTIES.tmp.$$"
    install -m 0644 -- "$SOURCE_PROPERTIES" "$TEMP_PROPERTIES"
    mv -f -- "$TEMP_PROPERTIES" "$INSTALLED_PROPERTIES"
    TEMP_PROPERTIES=""
    printf 'Replaced mod properties; previous file saved as %s\n' "$BACKUP_PROPERTIES"
else
    printf 'Preserving existing mod properties to retain its configuration.\n'
    printf 'Use --replace-properties to install the project template; the current file will be backed up.\n'
fi

printf 'Running Wurm patcher...\n'
cd -- "$WURM_LAUNCHER_DIR"
bash ./patcher.sh
printf 'Install and patch completed successfully.\n'
