#!/usr/bin/env bash

source "buildScript/init/env.sh"

cd sing-box
[ -f go.mod ] || exit 1

# Install gomobile
if [ ! -f "$GOPATH/bin/gomobile" ]; then
    go install -v github.com/sagernet/gomobile/cmd/gomobile
    go install -v github.com/sagernet/gomobile/cmd/gobind
fi

gomobile init
