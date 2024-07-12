# Step 1: Use the official NGINX image from the Docker Hub
FROM nginx:latest

# Step 2: Copy a custom NGINX configuration file into the container
# You can replace `nginx.conf` with your own NGINX configuration if needed
COPY nginx.conf /etc/nginx/nginx.conf

# Step 3: Copy static website files into the default NGINX HTML directory
# Replace `index.html` and other files with your own static website content
COPY /frontend-vue/dist /usr/share/nginx/html

# Step 4: Expose port 80 for the NGINX server
EXPOSE 80

# Step 5: Define the command to run NGINX in the foreground
CMD ["nginx", "-g", "daemon off;"]
