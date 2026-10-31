# Stage 1: Build Angular application
FROM node:22-alpine AS build
WORKDIR /app

# Cache dependencies
COPY package*.json ./
RUN npm ci --prefer-offline --no-audit || npm install

# Copy source and build production bundle
COPY . .
RUN npm run build -- --configuration production

# Stage 2: Serve with lightweight, secure Nginx
FROM nginx:alpine
WORKDIR /usr/share/nginx/html

# Clean default nginx files
RUN rm -rf ./*

# Copy production artifacts and custom proxy config
COPY --from=build /app/dist/web/browser ./
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

ENV NGINX_ENVSUBST_FILTER="BACKEND_URL"
ENV BACKEND_URL="http://backend:8081"

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
