#!/bin/sh
# usage: ./run.sh <runs> <parallel JVMs> <contexts per JVM> <scenario> <closeMode> [extra JVM options]
# Starts <runs> fresh JVMs (at most <parallel> at a time). Prints how many ended abnormally (exit code != 0).
# Needs: JDK 21+ (tested with Temurin 25.0.5), Maven, Linux amd64 or aarch64.
set -e
runs=${1:-20}; par=${2:-3}; n=${3:-1000}; scen=${4:-work}; mode=${5:-same}
if [ $# -gt 5 ]; then shift 5; else shift $#; fi
here=$(cd "$(dirname "$0")" && pwd)
mvn -q -f "$here/pom.xml" package dependency:build-classpath -Dmdep.outputFile="$here/target/cp.txt" -Dmdep.includeScope=runtime
CP="$here/target/classes:$(cat "$here/target/cp.txt")"
out="$here/target/out-$scen-$mode-$(date +%s)"; mkdir -p "$out"
export CP out n scen mode EXTRA="$*"
seq 1 "$runs" | xargs -P "$par" -I{} sh -c \
  'java -Xmx256m --enable-native-access=ALL-UNNAMED -XX:ErrorFile="$out/hs_err_{}_%p.log" $EXTRA -cp "$CP" repro.Repro "$n" "$scen" "$mode" 1 > "$out/run-{}.log" 2>&1; echo $? > "$out/run-{}.exit"'
bad=$(grep -L '^0$' "$out"/run-*.exit | wc -l | tr -d ' ')
echo "runs=$runs abnormal=$bad (details in $out)"
