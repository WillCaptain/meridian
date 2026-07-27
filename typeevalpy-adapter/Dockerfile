# TypeEvalPy target_tools/meridian — scaffold (extend when wiring CI images).
# Pattern follows pysonar2: JDK + tool jar + runner.py writing *_result.json.
FROM eclipse-temurin:21-jdk-jammy

WORKDIR /app

RUN apt-get update \
 && apt-get -y install python3 python3-pip git maven \
 && rm -rf /var/lib/apt/lists/*

# Expect build context to provide a Meridian distribution + wrapper named `meridian`
# on PATH, or set MERIDIAN_BIN at runtime.
COPY src /tmp/src

ENV MERIDIAN_BIN=/opt/meridian/bin/meridian
ENV PATH="/opt/meridian/bin:${PATH}"

CMD ["bash"]
