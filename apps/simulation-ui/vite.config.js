import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({plugins:[vue()],server:{host:'127.0.0.1',port:5174,strictPort:true,proxy:{'/admin-api':{target:'http://127.0.0.1:48080'},'/actuator':{target:'http://127.0.0.1:48080'},'/api/v1':{target:'http://127.0.0.1:48080'}}},build:{sourcemap:false}})
