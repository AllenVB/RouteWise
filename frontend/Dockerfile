# Geliştirme imajı. Üretim (nginx ile statik sunum) Faz 7'de eklenecek.
FROM node:22-slim

WORKDIR /srv

# Kilit dosyasıyla birebir kurulum: her derleme aynı sürümleri alsın.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

EXPOSE 5173
CMD ["npm", "run", "dev"]
