-- 可选：由你在 SQL 客户端手动执行，回到本教程的空白起点。
-- 会删除以下 7 个 demo 对象及其数据；不会删除数据库或其他对象。
-- 确认这些对象仍然只用于本教程后再执行；README 已内嵌相同清理步骤。
DROP TABLE IF EXISTS dbt_learning_demo.mart_customer_sales;
DROP TABLE IF EXISTS dbt_learning_demo.mart_daily_sales;
DROP TABLE IF EXISTS dbt_learning_demo.fct_orders;
DROP VIEW IF EXISTS dbt_learning_demo.stg_orders;
DROP VIEW IF EXISTS dbt_learning_demo.stg_customers;
DROP TABLE IF EXISTS dbt_learning_demo.raw_order_events;
DROP TABLE IF EXISTS dbt_learning_demo.raw_customers;
