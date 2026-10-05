# V1.2 契约与工程附件

`openapi.yaml` 是HTTP契约唯一权威；其中 x-database、枚举和 x-state-transitions 为DDL和状态投影的生成输入。YAML不可替代第一章业务签审、DEC或实际权限批准。文档和候选契约仍为待评审版本。

## 可复现流程
```bash
python -m pip install -r requirements-validation.txt
python generate_artifacts.py
python generate_artifacts.py --check
python validate_artifacts.py --require-external
```
只改YAML后必须重生成。`openapi.json`、目录、数据字典、DDL、状态和CSV不可分别手改。需求/版本检查/决策/前提属于管理源文件，不由HTTP契约擅自覆盖；追溯表由它们联合生成。

当前环境执行的是自建结构检查、JSON Schema 2020-12、生成物一致性、负例、CHECK布尔表达式和Python/Node HMAC交叉验证。完整OpenAPI校验器因依赖无法获取未执行；该事项在QA中明确SKIP。在有依赖的项目CI中必须使用`--require-external`，不能把SKIP当成全标准通过。

## 数据库
`schema_simulation.sql` 只面向专用空模拟库，候选MySQL 8.4，绝不直接导入已存在的芋道库。SQL中PK字典缩写转换为实际SQL关键字，所有FK后建以处理依赖。实际工程需要实体映射、回填、回滚和版本冻结。

DDL未在本次环境实际运行（没有MySQL）。SQLite只验证独立CHECK布尔真值，不能替代MySQL。MySQL执行必须验证：所有38表成功、组合FK、枚举、CHECK强制执行、maker/checker双向不同ID可行/同人拒绝/批准NULL拒绝、日关闭并发和事务回滚。`checker<>maker`不能独立保证checker非NULL，已补状态约束。

## 签名
`mock_signature.py`与`mock_signature_vector.json`共同给出SIM-HMAC-1字节向量。向量含公开的TEST_ONLY测试键，绝不能复制为服务密钥。真正服务需使用密钥管理、双环境隔离、入站唯一表与nonce去重。验签函数不实现业务授权、入站持久化或重放存储。

## 业务日
3.13定义的“重开”只允许复核并生成新报告版本，不允许对关闭日重写/新增历史分录。后到事件以原发生时间＋当前入账日追加。MySQL运行测试、异步任务重试和关账并发必须由实际工程实现验证，未在本包假装完成。

## 验收状态
保留原124检查ID，新加26=150。五种状态在checks.json定义；本次全部NOT_RUN。qa/static_validation.json中的PASS指产物检查，不是产品检查。APP源代码、AppID、实名审批和环境均未提供，不把文档交付当软件上线。
