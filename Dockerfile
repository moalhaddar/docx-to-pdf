# ---- Builder Stage ----
FROM rust:1-slim-bookworm AS builder

WORKDIR /project
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo "fn main() {}" > src/main.rs && cargo build --release && rm -rf src
COPY examples/ex1/input.docx ./examples/ex1/input.docx
COPY src ./src
RUN touch src/main.rs && cargo build --release

# ---- Runtime Stage ----
FROM debian:bookworm-slim
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        fontconfig \
        fonts-dejavu \
        fonts-liberation \
        fonts-noto-core \
        fonts-crosextra-carlito \
        fonts-crosextra-caladea \
        libreoffice-core-nogui \
        libreoffice-writer-nogui && \
    rm -f /usr/lib/libreoffice/program/libucpdav1.so \
          /usr/lib/libreoffice/program/libucpftp1.so && \
    groupadd -g 1000 app && \
    useradd -u 1000 -g 1000 -d /tmp -s /usr/sbin/nologin -M app && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /project
COPY --from=builder /project/target/release/docx-to-pdf /project/docx-to-pdf
COPY ./fonts/ /usr/share/fonts/custom
RUN fc-cache -f

USER 1000:1000
EXPOSE 8080

CMD ["/project/docx-to-pdf"]
