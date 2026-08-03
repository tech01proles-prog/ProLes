#!/bin/bash
set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Переходим в папку сервера
cd "$(dirname "$0")"

echo -e "${GREEN}╔══════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   🚀 Proles Deploy — Backend Only         ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════╝${NC}"
echo ""

# Проверяем что dist существует
if [ ! -d "dist" ]; then
    echo -e "${RED}❌ Папка dist/ не найдена!${NC}"
    echo -e "${YELLOW}   Сначала запустите: /root/build-frontend.sh${NC}"
    exit 1
fi

DIST_SIZE=$(du -sh dist | cut -f1)
echo -e "${GREEN}   📦 Frontend dist: $DIST_SIZE${NC}"
echo ""

echo -e "${GREEN}[1/3] Сборка Docker-образа...${NC}"
docker compose build server

echo -e "${GREEN}[2/3] Проверка во временном контейнере...${NC}"
docker rm -f server-verify 2>/dev/null || true
docker compose run -d --name server-verify -p 8081:8080 server

echo -e "${YELLOW}   ⏳ Ожидание healthcheck...${NC}"
RETRIES=0
MAX_RETRIES=12
until [ "$(docker inspect -f '{{.State.Health.Status}}' server-verify 2>/dev/null)" == "healthy" ]; do
    sleep 5
    RETRIES=$((RETRIES+1))
    if [ $RETRIES -ge $MAX_RETRIES ]; then
        echo -e "${RED}❌ Healthcheck failed!${NC}"
        docker logs --tail 30 server-verify
        docker rm -f server-verify
        echo -e "${GREEN}✅ Старый сервер работает${NC}"
        exit 1
    fi
    echo "   ... ($((RETRIES*5))с / 60с)"
done
echo -e "${GREEN}   ✅ Здоров!${NC}"

echo -e "${GREEN}[3/3] Переключение...${NC}"
docker compose stop server
docker compose rm -f server
docker compose up -d server
docker rm -f server-verify

docker image prune -f

echo ""
echo -e "${GREEN}╔══════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   🎉 Deploy успешен!                       ║${NC}"
echo -e "${GREEN}║   🌐 http://$(hostname -I | awk '{print $1}'):8080       ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════╝${NC}"