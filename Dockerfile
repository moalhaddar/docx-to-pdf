FROM ubuntu:20.04
ENV DEBIAN_FRONTEND=noninteractive 
RUN apt update
RUN apt install -y curl gnupg2
RUN curl -sS https://dl.yarnpkg.com/debian/pubkey.gpg | apt-key add -
RUN curl -fsSL https://deb.nodesource.com/setup_17.x | bash -
RUN echo "deb https://dl.yarnpkg.com/debian/ stable main" | tee /etc/apt/sources.list.d/yarn.list
RUN apt update
RUN apt install -y libreoffice
RUN apt install -y yarn nodejs
RUN apt install -y nodejs



RUN mkdir /root/pdf_generator
WORKDIR /root/pdf_generator

COPY ./package.json .
COPY ./yarn.lock .
RUN yarn


COPY ./src ./src
# Copy all the fonts as some might be missing from the default installation
ADD ./fonts /usr/share/fonts 

ENTRYPOINT ["yarn", "start"]