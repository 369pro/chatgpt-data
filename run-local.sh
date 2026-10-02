#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ ! -f .env ]]; then
  echo 'Missing .env. Configure the values listed in .env.example first.' >&2
  exit 1
fi
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [[ "${1:-}" != "--no-build" ]]; then
  mvn -q clean package -DskipTests
elif [[ ! -f chatgpt-data-app/target/chatgpt-data-app.jar ]] ||
     [[ -n "$(find chatgpt-data-*/src -type f -newer chatgpt-data-app/target/chatgpt-data-app.jar -print -quit)" ]] ||
     [[ -n "$(find . -maxdepth 2 -name pom.xml -newer chatgpt-data-app/target/chatgpt-data-app.jar -print -quit)" ]]; then
  echo 'The backend jar is missing or older than source files. Run ./run-local.sh to rebuild.' >&2
  exit 1
fi
exec java -jar chatgpt-data-app/target/chatgpt-data-app.jar --spring.profiles.active=dev
