# ---- Base Stage ----
FROM ubuntu:20.04 AS base
ENV DEBIAN_FRONTEND=noninteractive
RUN apt update && \
    apt upgrade -y && \
    apt install -y software-properties-common && \
    add-apt-repository ppa:libreoffice/ppa && \
    apt update && \
    apt install -y libreoffice && \
    apt clean && \
    rm -rf /var/lib/apt/lists/* 


# ---- Node Stage ----
FROM base AS node
RUN apt-get update && \
    apt-get install -y ca-certificates curl gnupg && \
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg

ARG NODE_MAJOR=20
RUN echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_$NODE_MAJOR.x nodistro main" | tee /etc/apt/sources.list.d/nodesource.list

RUN apt-get update && \
    apt-get install nodejs -y && \
    npm install -g yarn


# ---- Unoserver Stage ----
FROM node as node_n_python
RUN apt-get install python3-pip -y
RUN pip3 install unoserver


# ---- Build Stage ----
FROM node AS build
RUN mkdir /tmp/generated_pdfs && \
    mkdir /tmp/uploaded_docx && \
    mkdir /tmp/libreoffice_profiles && \
    mkdir /docx-to-pdf

WORKDIR /docx-to-pdf

COPY ./package.json .
COPY ./yarn.lock .
RUN yarn && yarn cache clean

COPY ./src ./src


# ---- Release Stage ----
FROM node_n_python AS release

COPY --from=build /tmp /tmp
COPY --from=build /docx-to-pdf /docx-to-pdf
ADD ./fonts /usr/share/fonts

WORKDIR /docx-to-pdf

ARG PORT=9999
ENV CLEANUP_AUTOMATION_DRY_MODE=OFF \
    CLEANUP_AUTOMATION_INTERVAL_MS=50000 \
    PORT=${PORT} \
    FILE_MAX_AGE_IN_SECONDS=300

EXPOSE ${PORT}

ENTRYPOINT ["yarn", "start:production"]