# MinIO 已从公共镜像仓库撤下预构建镜像：Docker Hub 的 minio/minio 仓库整个删除
# （2026-10 实测 `object not found`，CI 全新拉取必挂），quay.io/minio/minio 匿名拉取 401，
# 连 dl.min.io 的下载路径都返回 410 Gone。官方剩余的分发渠道只有 GitHub Releases 的二进制。
# 这里按官方 README 的「自建镜像」指引，从 Releases 下载二进制并校验 sha256 后打包，
# compose 从此不依赖任何第三方镜像源。升级 MinIO 时改 MINIO_RELEASE 即可。
#
# 二进制是静态编译的，alpine 足够；保持 root 运行与官方镜像默认一致，
# 避免 named volume 首次挂载时的属主问题。
FROM alpine:3.21

ARG MINIO_RELEASE=RELEASE.2025-09-07T16-13-09Z

WORKDIR /tmp
# 校验文件里登记的名字是 minio.<RELEASE>（不带平台段），二进制存成同名才能 sha256sum -c
ADD https://github.com/minio/minio/releases/download/${MINIO_RELEASE}/minio.linux-amd64.${MINIO_RELEASE} /tmp/minio.${MINIO_RELEASE}
ADD https://github.com/minio/minio/releases/download/${MINIO_RELEASE}/minio.linux-amd64.${MINIO_RELEASE}.sha256sum /tmp/minio.sha256sum
RUN sha256sum -c minio.sha256sum \
    && install -m 0755 "minio.${MINIO_RELEASE}" /usr/bin/minio \
    && rm -rf /tmp/*

EXPOSE 9000 9001

ENTRYPOINT ["/usr/bin/minio"]
CMD ["server", "/data", "--console-address", ":9001"]
