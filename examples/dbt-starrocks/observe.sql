-- 按 README 所在阶段选中对应片段执行，不要一次执行整个文件。
-- 查询尚未创建的对象会报错，这是需要观察的生命周期。

-- A：任意阶段，查看真实存在的表与视图。
SELECT TABLE_NAME, TABLE_TYPE
FROM information_schema.tables
WHERE TABLE_SCHEMA = 'dbt_learning_demo'
ORDER BY TABLE_NAME;

-- B：seed 之后，全部 7 条事件；1003 有两个版本。
SELECT * FROM dbt_learning_demo.raw_order_events ORDER BY event_id;

-- C：staging 之后，查看视图定义与当前可见订单。
SHOW CREATE VIEW dbt_learning_demo.stg_orders;
SELECT order_id, status, amount, updated_at
FROM dbt_learning_demo.stg_orders ORDER BY order_id;

-- D：fact 之后，确认物理 PRIMARY KEY 与实体数据。
SHOW CREATE TABLE dbt_learning_demo.fct_orders;
SELECT order_id, status, amount, paid_amount, updated_at
FROM dbt_learning_demo.fct_orders ORDER BY order_id;
SELECT COUNT(*) AS order_count, SUM(paid_amount) AS paid_amount
FROM dbt_learning_demo.fct_orders;

-- E：marts 之后，观察实体表的 DDL 与业务结果。
SHOW CREATE TABLE dbt_learning_demo.mart_daily_sales;
SELECT * FROM dbt_learning_demo.mart_daily_sales ORDER BY order_date;
SELECT * FROM dbt_learning_demo.mart_customer_sales ORDER BY customer_id;

-- F：第二批每推进一层执行一次，对比各层是否已更新。
SELECT 'stg_orders' AS layer, COUNT(*) AS order_count,
       SUM(CASE WHEN status = 'paid' THEN amount ELSE 0 END) AS paid_amount
FROM dbt_learning_demo.stg_orders
UNION ALL
SELECT 'fct_orders', COUNT(*), SUM(paid_amount)
FROM dbt_learning_demo.fct_orders
UNION ALL
SELECT 'mart_daily_sales', SUM(order_count), SUM(paid_amount)
FROM dbt_learning_demo.mart_daily_sales;
