# Geliştirme imajı. Üretim (nginx ile statik sunum) Faz 7'de eklenecek.
FROM node:22-slim

WORKDIR /srv

COPY package.json ./
RUN npm install

COPY . .

EXPOSE 5173
CMD ["npm", "run", "dev"]
