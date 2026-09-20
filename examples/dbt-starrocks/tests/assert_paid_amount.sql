-- 待支付和取消订单不计入收入；金额必须非负，空值由 schema 测试覆盖。
select order_id
from {{ ref('fct_orders') }}
where amount < 0
   or paid_amount <> case when status = 'paid' then amount else 0 end
