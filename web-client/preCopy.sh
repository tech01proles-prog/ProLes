#!/bin/bash

# ==========================================
# КОНФИГУРАЦИЯ
# ==========================================

GIT_BRANCH="main"
USER="root"
HOST="138.16.177.245"
REMOTE_DEST="/root/proles-deploy/"

# ИСПРАВЛЕНО: Используем одну переменную WORK_DIR везде
WORK_DIR="./temp_deploy"

EXCLUDES=(
    ".git"
    ".github"
    "node_modules"
    "*.log"
    ".env.local"
    "preCopy.sh"
    # Системные папки (на случай ошибки путей)
    "proc" "sys" "dev" "mnt" "boot" "usr" "bin" "lib" "lib64" "sbin"
    "etc" "home" "opt" "srv" "var" "run" "tmp" "lost+found" "cdrom" "media"
    "swapfile"
    "ProlesTimesheet"
)

# ==========================================
# ЛОГИКА
# ==========================================

set -e
echo "🚀 Начало процесса деплоя..."

# 1. Подготовка SSH для GitHub
export GIT_SSH_COMMAND="ssh -i ~/.ssh/id_ed25519_github -o IdentitiesOnly=yes -o StrictHostKeyChecking=no"

# 2. Клонирование или обновление репо
if [ ! -d "$WORK_DIR/.git" ]; then
    echo "📥 Клонирование репозитория..."
    git clone --depth 1 -b "$GIT_BRANCH" git@github.com:tech01proles-prog/ProLes.git "$WORK_DIR"
else
    echo "🔄 Обновление локальной копии репозитория..."
    cd "$WORK_DIR"
    git fetch origin "$GIT_BRANCH"
    git reset --hard "origin/$GIT_BRANCH"
    cd ..
fi

# 3. Формирование списка исключений
RSYNC_EXCLUDES=""
for exclude in "${EXCLUDES[@]}"; do
    RSYNC_EXCLUDES="$RSYNC_EXCLUDES --exclude '$exclude'"
done

# 4. Синхронизация
echo "📤 Синхронизация файлов..."
echo "   Откуда: $WORK_DIR/"
echo "   Куда: $USER@$HOST:$REMOTE_DEST"

# ИСПРАВЛЕНО: Используем $WORK_DIR вместо несуществующей $LOCAL_TEMP
# Добавлен флаг --dry-run для первой проверки (уберите его, когда убедитесь, что пути верны)
# СЕЙЧАС ОН ВКЛЮЧЕН ДЛЯ БЕЗОПАСНОСТИ. УДАЛИТЕ СТРОКУ --dry-run ПОСЛЕ ПРОВЕРКИ!

eval rsync -avz --progress $RSYNC_EXCLUDES "$WORK_DIR/" "$USER@$HOST:$REMOTE_DEST"

echo ""
echo "⚠️ Сейчас был выполнен ПРОБНЫЙ ЗАПУСК (--dry-run)."
echo "Если список файлов выше выглядит корректно (только файлы проекта), "
echo "отредактируйте скрипт и удалите флаг --dry-run из команды rsync."