#!/bin/bash
# Downloads FiveK expert-C shards one at a time, extracts the pairs (fivek_extract.py) and deletes the
# shard (each is about 2 GB). Needs Python with pyarrow and Pillow. Work folder: $ENHANCE_WORK.
set -e
H=$(cd "$(dirname "$0")" && pwd)
W=${ENHANCE_WORK:-$H/../dl/enhance}; mkdir -p "$W/fivek"; cd "$W"
for spec in "train-00000-of-00044 tr0_" "train-00001-of-00044 tr1_" "train-00002-of-00044 tr2_" "train-00003-of-00044 tr3_" "test-00000-of-00013 te0_" "test-00001-of-00013 te1_"; do
  set -- $spec
  curl -sS -f -L -o fivek/shard.parquet "https://huggingface.co/datasets/logasja/mit-adobe-fivek/resolve/main/c/$1.parquet" || exit 1
  python3 -I "$H/fivek_extract.py" fivek/shard.parquet fivek/pairs $2 > fivek/log_$2.txt 2>&1 || exit 1
  rm -f fivek/shard.parquet
  echo "done $1"
done
