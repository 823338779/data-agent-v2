{{ config(distributed_by=['customer_id']) }}

-- 从客户出发做 LEFT JOIN，未消费客户也保留一行，收入归一为 0。
select
    c.customer_id, c.customer_name, c.city,
    count(o.order_id) as order_count,
    coalesce(sum(o.paid_amount), 0) as paid_amount
from {{ ref('stg_customers') }} c
left join {{ ref('fct_orders') }} o on c.customer_id = o.customer_id
group by c.customer_id, c.customer_name, c.city
