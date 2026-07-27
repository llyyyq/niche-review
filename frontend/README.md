# Frontend

本目录只保存项目自身的静态前端和可复用的 Nginx 配置示例，不保存完整 Nginx 运行包。

```text
frontend/
├── app/                  # HTML、CSS、JavaScript、图标与基础图片
│   └── imgs/blogs/       # 运行时博客上传目录，仅保留 .gitkeep
└── nginx/
    └── nginx.conf.example
```

## 本地运行

1. 下载或安装任意 Nginx 发行版。
2. 将 `app/` 的内容复制到 `<nginx>/html/hmdp/`。
3. 将 `nginx/nginx.conf.example` 的 `server` 配置合并到 `<nginx>/conf/nginx.conf`。
4. 启动后端 `http://127.0.0.1:8081`，再访问 `http://127.0.0.1:8080`。

`imgs/blogs/` 是后端上传博客图片时 Nginx 对外提供静态文件的位置，目录中的运行时文件不提交 Git。
