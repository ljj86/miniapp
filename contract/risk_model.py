"""第五章纯假设模型复算。仅标准库，无生产信用评价/资金操作。
运行 python risk_model.py，将同目录生成 risk_model_result.json。
"""
from math import comb, exp, lgamma, ceil
from pathlib import Path
import json

def beta_binomial(n: int, alpha: float, beta: float):
    if n < 1 or alpha <= 0 or beta <= 0: raise ValueError('invalid parameters')
    logb=lambda a,b:lgamma(a)+lgamma(b)-lgamma(a+b)
    return [exp(lgamma(n+1)-lgamma(k+1)-lgamma(n-k+1)+logb(k+alpha,n-k+beta)-logb(alpha,beta)) for k in range(n+1)]

def summary(pmf, unit_loss=80.0, deposit=300.0):
    if abs(sum(pmf)-1)>1e-8:raise ValueError('probability sum not one')
    return {'total_loss_mean':sum(p*k*unit_loss for k,p in enumerate(pmf)),
            'merchant_loss_mean':sum(p*min(k*unit_loss,deposit) for k,p in enumerate(pmf)),
            'platform_loss_mean':sum(p*max(k*unit_loss-deposit,0) for k,p in enumerate(pmf)),
            'probability_exceeding_deposit':sum(p for k,p in enumerate(pmf) if k*unit_loss>deposit)}

def main():
    n,p,rho=50,0.03,0.10
    out={'warning':'全部参数均为假设，不是真实坏账、存款利率、产品报价或需求基线；不用于真实准入。',
         'assumptions':{'users':n,'balance_each':100,'pd':p,'lgd':0.8,'merchant_deposit':300,'correlation':rho},
         'independent':summary([comb(n,k)*p**k*(1-p)**(n-k) for k in range(n+1)]),
         'correlated':summary(beta_binomial(n,0.27,8.73)),
         'profit_5000_calls':0.07*5000+300+83.33-3000-500,
         'profit_50000_calls':0.07*50000+300+83.33-3000-500,
         'break_even_calls':ceil((3000+500-300-83.33)/0.07),
         'zero_defaults_100_upper_95':1-0.05**(1/100)}
    for name in ('independent','correlated'):
        s=out[name]
        assert abs(s['merchant_loss_mean']+s['platform_loss_mean']-s['total_loss_mean'])<1e-7
    path=Path(__file__).with_name('risk_model_result.json');path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(out,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
