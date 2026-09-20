-- 返回异常行即测试失败；这些固定预期仅适用于随项目提供的学习数据。
select count(*) as order_count, sum(paid_amount) as paid_amount
from {{ ref('fct_orders') }}
having count(*) <> {{ 5 if var('demo_batch') | int == 1 else 6 }}
    or coalesce(sum(paid_amount), 0) <> {{ 400 if var('demo_batch') | int == 1 else 495 }}
