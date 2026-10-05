import { createApp, nextTick } from 'vue'
import App from '../src/App.vue'
import router from '../src/router/index.js'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import request from '../src/utils/request.js'
export { router, request, nextTick }
export async function mount(){const app=createApp(App);app.use(router);app.use(ElementPlus,{locale:zhCn,size:'small'});await router.isReady();app.mount('#app');await nextTick();return app}

export {attachServiceSession,connectService,useDemoMode,serviceConnection} from '../src/utils/service-mode.js'
