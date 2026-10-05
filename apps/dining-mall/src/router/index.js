import { createRouter, createWebHashHistory } from 'vue-router'
import { projectName } from '../../config/config.default'

const routes = [
  {path:'/mall/:view',component:()=>import('../views/Mall.vue'),meta:{title:'鲜食好店'}} ,
  //通用路由
  {
    path: '/',
    name: '/',
    component: () => import('../views/Login.vue'),
    meta: {
      title: '登录'
    }
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: {
      title: '登录'
    }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('../views/Register.vue'),
    meta: {
      title: '注册'
    }
  },
  {
    path: '/404',
    name: '404',
    component: () => import('../views/404.vue'),
    meta: {
      title: '404'
    }
  },
  //下面都是前台路由
  {
    path: '/front',
    name: 'Front',
    component: () => import('../views/Front.vue'),
    children: [
      // 前台子路由
      {
        path: 'home',
        name: 'FrontHome',
        component: () => import('../views/front/Home.vue'),
        meta: {
          title: '前台首页'
        }
      },
      {
        path: 'password',
        name: 'FrontPassword',
        component: () => import('../views/front/Password.vue'),
        meta: {
          title: '修改密码'
        }
      },
      {
        path: 'person',
        name: 'FrontPerson',
        component: () => import('../views/front/Person.vue'),
        meta: {
          title: '个人信息'
        }
      },
      {
        path: 'address',
        name: 'FrontAddress',
        component: () => import('../views/front/Address.vue'),
        meta: {
          title: '收货地址'
        }
      },
      {
        path: 'goods',
        name: 'FrontGoods',
        component: () => import('../views/front/Goods.vue'),
        meta: {
          title: '全部商品'
        }
      },
      {
        path: 'goodsDetail',
        name: 'FrontGoodsDetail',
        component: () => import('../views/front/GoodsDetail.vue'),
        meta: {
          title: '商品详情'
        }
      },
      {
        path: 'cart',
        name: 'FrontCart',
        component: () => import('../views/front/Cart.vue'),
        meta: {
          title: '购物车'
        }
      },
      {
        path: 'orders',
        name: 'FrontOrders',
        component: () => import('../views/front/Orders.vue'),
        meta: {
          title: '我的订单'
        }
      },
      {
        path: 'collect',
        name: 'FrontCollect',
        component: () => import('../views/front/Collect.vue'),
        meta: {
          title: '我的收藏'
        }
      },
      {
        path: 'unit',
        name: 'FrontUnit',
        component: () => import('../views/front/Unit.vue'),
        meta: {
          title: '店铺详情'
        }
      },
      {
        path: 'search',
        name: 'FrontSearch',
        component: () => import('../views/front/Search.vue'),
        meta: {
          title: '商品搜索'
        }
      },
      // 前台子路由
    ]
  },
  //下面都是后台路由
  {
    path: '/back',
    name: 'back',
    component: () => import('../views/Back.vue'),
    children: [
      {path:'materials',name:'BackMaterials',component:()=>import('../views/back/ServiceSettings.vue'),meta:{title:'资料管理'}},
      {path:'support',name:'BackSupport',component:()=>import('../views/back/SupportWorkspace.vue'),meta:{title:'客服与留言'}},
      {path:'afterSales',name:'BackAfterSales',component:()=>import('../views/back/AfterSales.vue'),meta:{title:'售后与评价'}},
      {path:'paymentManagement',name:'BackpaymentManagement',component:()=>import('../views/back/ServiceSettings.vue'),meta:{title:'系统支付管理'}},
      {path:'payWechat',name:'BackpayWechat',component:()=>import('../views/back/ServiceSettings.vue'),meta:{title:'微信支付接入'}},
      {path:'payBank',name:'BackpayBank',component:()=>import('../views/back/ServiceSettings.vue'),meta:{title:'银行卡支付接入'}},
      {path:'payAlipay',name:'BackpayAlipay',component:()=>import('../views/back/ServiceSettings.vue'),meta:{title:'支付宝支付接入'}},
      {path:'aiManagement',name:'BackaiManagement',component:()=>import('../views/back/Integrations.vue'),meta:{title:'AI客服管理'}},
      {path:'aiConfiguration',name:'BackaiConfiguration',component:()=>import('../views/back/ServiceSettings.vue'),meta:{title:'AI配置与连接'}},
      {path:'aiSessions',name:'BackaiSessions',component:()=>import('../views/back/SupportWorkspace.vue'),meta:{title:'客服会话与消息'}},
      {path:'credit',name:'BackCredit',component:()=>import('../views/back/Credit.vue'),meta:{title:'先吃后付工作台'}},
      // 后台子路由
      {
        path: 'home',
        name: 'BackHome',
        component: () => import('../views/back/Home.vue'),
        meta: {
          title: '后台首页'
        }
      },
      {
        path: 'password',
        name: 'BackPassword',
        component: () => import('../views/back/Password.vue'),
        meta: {
          title: '修改密码'
        }
      },
      {
        path: 'adminPerson',
        name: 'BackAdminPerson',
        component: () => import('../views/back/AdminPerson.vue'),
        meta: {
          title: '个人信息'
        }
      },
      {
        path: 'user',
        name: 'BackUser',
        component: () => import('../views/back/User.vue'),
        meta: {
          title: '用户管理'
        }
      },
      {
        path: 'admin',
        name: 'BackAdmin',
        component: () => import('../views/back/Admin.vue'),
        meta: {
          title: '管理员管理'
        }
      },
      {
        path: 'type',
        name: 'BackType',
        component: () => import('../views/back/Type.vue'),
        meta: {
          title: '分类管理'
        }
      },
      {
        path: 'tag',
        name: 'BackTag',
        component: () => import('../views/back/Tag.vue'),
        meta: {
          title: '标签管理'
        }
      },
      {
        path: 'unit',
        name: 'BackUnit',
        component: () => import('../views/back/Unit.vue'),
        meta: {
          title: '商家管理'
        }
      },
      {
        path: 'unitPerson',
        name: 'BackUnitPerson',
        component: () => import('../views/back/UnitPerson.vue'),
        meta: {
          title: '个人信息'
        }
      },
      {
        path: 'address',
        name: 'BackAddress',
        component: () => import('../views/back/Address.vue'),
        meta: {
          title: '地址管理'
        }
      },
      {
        path: 'goods',
        name: 'BackGoods',
        component: () => import('../views/back/Goods.vue'),
        meta: {
          title: '商品管理'
        }
      },
      {
        path: 'notice',
        name: 'BackNotice',
        component: () => import('../views/back/Notice.vue'),
        meta: {
          title: '公告管理'
        }
      },
      {
        path: 'banner',
        name: 'BackBanner',
        component: () => import('../views/back/Banner.vue'),
        meta: {
          title: '轮播图管理'
        }
      },
      {
        path: 'collect',
        name: 'BackCollect',
        component: () => import('../views/back/Collect.vue'),
        meta: {
          title: '收藏管理'
        }
      },
      {
        path: 'orders',
        name: 'BackOrders',
        component: () => import('../views/back/Orders.vue'),
        meta: {
          title: '订单管理'
        }
      },
      {
        path: 'echarts',
        name: 'BackEcharts',
        component: () => import('../views/back/Echarts.vue'),
        meta: {
          title: '数据统计'
        }
      },
      // 后台子路由
    ]
  },
]

const backendRoutes = routes.find(r=>r.path==='/back')
for(const base of ['/merchant','/platform']) routes.push({...backendRoutes,path:base,name:base,children:backendRoutes.children.map(r=>({...r,name:base+r.name}))})

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes
})

// 全局前置守卫
router.beforeEach((to, from, next) => {
  const account = JSON.parse(sessionStorage.getItem("account") || '{}')
  if(to.path==='/404')return next('/mall/notFound')
  const ownBase=account.role==='ROLE_UNIT'?'/merchant':'/platform'
  if(to.path.startsWith('/front')){
    const pages={home:'home',goods:'category',goodsDetail:'detail',search:'category',unit:'shop',collect:'category',person:'profile',password:'profile',service:'support',chat:'support',message:'supportTickets',manual:'manuals',updateLog:'updates',updatelog:'updates',address:'address',cart:'cart',orders:'orders'}
    const page=to.path.split('/')[2]||'home'
    return next({path:'/mall/'+(pages[page]||'notFound'),query:{...(to.query||{}),...(page==='collect'?{favorites:1}:{})},replace:true})
  }
  if(to.path.startsWith('/mall/') && !['home','category','detail','cart','orders','mine','checkout','bill','address','profile','shops','shop','orderDetail','deferredOrder','orderService','afterSales','afterSale','notFound','support','supportChat','supportTickets','supportNew','supportTicket','manuals','manual','materials','updates'].includes(to.path.split('/')[2]))return next('/mall/home')
  if(/^\/mall\/(cart|orders|orderDetail|deferredOrder|orderService|afterSales|afterSale|checkout|address|profile|bill|supportChat|supportTickets|supportNew|supportTicket|manuals|manual|materials|updates)$/.test(to.path) && account.role!=='ROLE_USER')return next({path:'/login',query:{returnTo:to.fullPath||to.path}})
  if(to.path.startsWith('/back') && ['ROLE_ADMIN','ROLE_UNIT'].includes(account.role))return next(to.path.replace('/back',ownBase))
  if(to.path.startsWith('/merchant')||to.path.startsWith('/platform')){
    if(!['ROLE_ADMIN','ROLE_UNIT'].includes(account.role))return next('/login')
    if(!to.path.startsWith(ownBase))return next(ownBase+'/home')
    if(to.path===ownBase)return next(ownBase+'/home')
    if(account.role==='ROLE_UNIT'&&!['home','credit','afterSales','support','echarts','goods','orders','unitPerson','password'].includes(to.path.split('/')[2]))return next(ownBase+'/home')
  }
  if (to.path === '/front') return next('/mall/home')
  if (to.path === '/back') return next('/back/credit')
  if (to.path === '/register') return next('/login')
  if (to.path.startsWith('/back') && !['ROLE_ADMIN','ROLE_UNIT'].includes(account.role)) return next('/login')
  if (to.path.startsWith('/back') && account.role === 'ROLE_UNIT' && !['/back/home','/back/credit','/back/afterSales','/back/support','/back/echarts','/back/goods','/back/orders','/back/unitPerson','/back/password'].includes(to.path)) return next('/back/credit')
  if (to.matched.length === 0) {
    next('/mall/notFound')
    return
  }
  if (to.path === '/') {
    if (account.role) {
      // 现在是只有角色为管理员才访问后台
      // 如果想设置其他角色登录后也默认访问后台，可以用下面的判断条件
      if (account.role === 'ROLE_ADMIN' || account.role === 'ROLE_UNIT') {
        next('/back/credit')
      } else {
        next('/mall/home')
      }
    } else {
      // 现在是只有登录以后才可以访问首页
      // next('/login')
      // 如果想不登录就可以直接访问首页的话，直接用下面的跳转/front/home即可
      next('/mall/home')
    }
  } else {
    next()
  }
})

// 全局后置守卫
router.afterEach((to) => {
  document.title = to.meta.title && to.meta.title !== projectName ? `${to.meta.title} - ${projectName}` : projectName // 设置页面标题
})

export default router
