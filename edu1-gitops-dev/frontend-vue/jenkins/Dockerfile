FROM nginx:1.17.9-alpine

ENV TZ Asia/Seoul
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone 

COPY ./dist /usr/share/nginx/html
COPY ./edu1-gitops-dev/frontend-vue/jenkins/vue-nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
