import * as credit from '../src/utils/credit-service.js'
import * as feedback from '../src/utils/feedback-service.js'
import * as support from '../src/utils/support-service.js'
import * as resources from '../src/utils/local-service-resources.js'
import * as help from '../src/utils/help-builtins.js'
import {serviceKey} from '../src/utils/service-files.js'
export const requestTestGlobals={...credit,...feedback,...support,...resources,...help,serviceKey,URLSearchParams,serviceConnection:{mode:'demo',revision:0},isBackendMode:()=>false,useDemoMode:()=>{},backendRequest:()=>{throw Error('Local test must not call backend')}}
