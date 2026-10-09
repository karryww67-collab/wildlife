FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm config set registry https://registry.npmmirror.com && npm install
COPY . .
# vue-tsc 类型检查 + vite 打包。
# src/ 下的类型错误已清零（formatTime 收 null、ResultExportQuery 补 startTime/endTime、
#   BatchReviewPayload 补定义、StatisticsOverview 补检出率字段、/models/active 用
#   NoActiveModel 判别联合类型）。
# 因此恢复成 package.json 的 build 脚本，类型一旦回退构建就直接失败，而不是带病出包。
RUN npm run build

FROM nginx:alpine
# 移除官方 entrypoint 的 10-listen-on-ipv6-by-default.sh。
#
# 它唯一的动作是把镜像自带的 default.conf 改成监听 [::]:80 —— 而本项目
# 紧接着就用自己的 nginx.conf 覆盖了那个文件（见下一行 COPY），这个改动根本留不下来。
# 代价却很实在：脚本内部要跑 `apk manifest nginx`，在无外网 / 受限环境里会卡住，
# 表现是容器 `Up` 但 9095 一直连不上（entrypoint 卡在第 3 行日志就不动了）。
# 少了它，容器启动是确定的。
RUN rm -f /docker-entrypoint.d/10-listen-on-ipv6-by-default.sh

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
