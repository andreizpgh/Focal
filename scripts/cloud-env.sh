#!/usr/bin/env bash
# Source this only in the prepared Codex cloud environment.
export JAVA_HOME=/workspace/toolchains/jdk-17.0.17+10
export ANDROID_HOME=/workspace/toolchains/android-sdk
export GRADLE_USER_HOME=/workspace/toolchains/gradle-cache
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmake/3.22.1/bin:$PATH"
if [[ ${FOCAL_PROXY_INITIALIZED:-0} != 1 ]]; then
    if [[ -f /etc/ssl/certs/java/cacerts ]]; then
        export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
    fi
    if [[ -n ${HTTPS_PROXY:-} ]]; then
        focal_proxy_flags=$(python3 - <<'PY'
import os
from urllib.parse import urlsplit
p = urlsplit(os.environ['HTTPS_PROXY'])
if p.hostname and p.port:
    print(f'-Dhttps.proxyHost={p.hostname} -Dhttps.proxyPort={p.port} -Dhttp.proxyHost={p.hostname} -Dhttp.proxyPort={p.port} -Dhttp.nonProxyHosts=localhost|127.*')
PY
)
        export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $focal_proxy_flags"
        unset focal_proxy_flags
    fi
    export FOCAL_PROXY_INITIALIZED=1
fi
