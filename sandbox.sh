#!/usr/bin/env bash

docker run -it --rm \
  -e LANG=C.UTF-8 -e LC_ALL=C.UTF-8 \
  -v nix-store:/nix \
  -v jbang-cache:/root/.jbang \
  -v "$(pwd)":/project \
  -w /project \
  nixos/nix nix-shell -p python3 nodejs git temurin-bin-25 '(jbang.override { jdk = jdk25; })' perl jq curl git