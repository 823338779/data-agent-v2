-- 订单粒度事实表保留所有状态，收入的支付口径由语义层统一定义。
select
    order_id,
    customer_id,
    cast(ordered_at as date) as ordered_at,
    status,
    cast(amount as decimal(12, 2)) as amount,
    cast(cost as decimal(12, 2)) as cost
from {{ ref('raw_orders') }}
