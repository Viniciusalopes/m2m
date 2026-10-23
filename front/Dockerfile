# syntax=docker/dockerfile:1
FROM node:24-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npx ng build --configuration production

FROM nginx:alpine
RUN apk add --no-cache openssl
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/40-m2m-tls.sh /docker-entrypoint.d/40-m2m-tls.sh
COPY nginx/50-m2m-config.sh /docker-entrypoint.d/50-m2m-config.sh
COPY --from=build /build/dist/front/browser /usr/share/nginx/html
EXPOSE 80 443
