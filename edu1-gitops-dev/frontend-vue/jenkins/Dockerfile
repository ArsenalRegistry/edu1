FROM nginx:1.17.9-alpine

# Copy the built files from the host machine to the Nginx HTML directory
COPY ./frontend-vue/dist /usr/share/nginx/html

# Copy the Nginx configuration file
COPY ./edu1-gitops-dev/frontend-vue/jenkins/vue-nginx.conf /etc/nginx/conf.d/default.conf

# Expose port 80
EXPOSE 80

# Start Nginx
CMD ["nginx", "-g", "daemon off;"]
