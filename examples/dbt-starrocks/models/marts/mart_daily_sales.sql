{{ config(distributed_by=['order_date']) }}

-- 聚合表每次全量重建，使旧订单状态变化能够回算原订单日期的收入。
select
    order_date,
    count(*) as order_count,
    sum(case when status = 'paid' then 1 else 0 end) as paid_order_count,
    sum(paid_amount) as paid_amount
from {{ ref('fct_orders') }}
group by order_date
