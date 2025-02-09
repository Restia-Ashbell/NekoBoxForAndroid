#!/usr/bin/env bash

source buildScript/init/env.sh
export CGO_ENABLED=1
export GO386=softfloat

source buildScript/init/env_ndk.sh

cd sing-box
rel=1
LATEST_TAG=$(git ls-remote --tags https://github.com/SagerNet/sing-box.git | grep -o 'refs/tags/[^\^]*' | sed 's/refs\/tags\///' | sort -V | tail -n 1)
COMMIT_HASH=$(git rev-parse --short HEAD)
gomobile bind -v -androidapi 21 -trimpath -buildvcs=false -ldflags="-X github.com/sagernet/sing-box/constant.Version=${LATEST_TAG}-${COMMIT_HASH} -s -w -buildid=" -tags="with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_ech,with_shadowsocksr" ./experimental/libbox || exit 1
rm -r libbox-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libbox.aar $proj
echo ">> install $(realpath $proj)/libbox.aar"
