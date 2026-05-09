# 小萝卜机器人 H5 控制台 (xlb-frontend)

机器人远程控制面板，支持：
- 方向控制（前进/后退/左转/右转/绕圈）
- 表情切换
- Wi-Fi 配网
- 在线状态与电量显示

## 技术栈

- 纯原生 HTML + CSS + JS（零框架依赖）
- Vite 负责构建与开发服务器

## 目录结构

```
xlb-frontend/
├── src/
│   ├── index.html
│   ├── style.css
│   └── app.js
├── dist/          # 构建输出（Git 忽略）
├── package.json
├── vite.config.js
└── README.md
```

## 快速开始

```bash
npm install
npm run dev      # 开发服务器 http://localhost:3000
npm run build    # 构建到 dist/ 目录
```

## 配置 API 地址

默认使用同域相对路径 `/api`。如需指向其他后端地址，创建 `.env.local`：

```
VITE_API_BASE=https://api.chujian.site
```

## 部署到域名

构建产物在 `dist/` 目录，上传到 `h5.chujian.site` 即可。
参考 Nginx 配置：

```nginx
server {
    listen 80;
    server_name h5.chujian.site;
    root /var/www/xlb-frontend/dist;
    index index.html;
    location / {
        try_files $uri $uri/ /index.html;
    }
    location /api/ {
        proxy_pass http://127.0.0.1:8000;
    }
}
```
