#!/bin/bash
# run.sh --wrap for a queued run: the texture-compression probe (tools/TexCompProbe.java, classes compiled into
# harness/texprobe/classes) runs first on the machine's GPU, output -> ~/Zomboid/pzopt-texprobe.out (run.sh copies
# pzopt-*.out into the run dir), then the game starts as usual. Probe arguments: harness/texprobe/args.txt (line 1
# program args, line 2 packs, every further line the JVM options of one probe invocation).
d=$(cd "$(dirname "$0")" && pwd)
g=$(dirname "$1")
[[ -d "$g/jre64" ]] || g="$g/projectzomboid"
packs=()
for p in $(sed -n 2p "$d/args.txt"); do packs+=("$g/media/texturepacks/$p"); done
out="$HOME/Zomboid/pzopt-texprobe.out"
: > "$out"
tail -n +3 "$d/args.txt" | while IFS= read -r opts; do
  echo "### probe $opts" >> "$out"
  timeout 110 "$g/jre64/bin/java" $opts --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true -Djava.library.path="$g/natives" \
    -cp "$g/projectzomboid.jar:$d/classes" TexCompProbe $(sed -n 1p "$d/args.txt") "${packs[@]}" >> "$out" 2>&1 < /dev/null
  echo "probe exit $?" >> "$out"
done
exec "$@"
