-- 先限制可见批次，再选每个订单的最新版本，避免未来批次提前参与计算。
-- 时间相同以 event_id 决胜；真实 CDC 应使用来源系统的稳定版本序号。
{% set batch = var('demo_batch') | int %}
{% if batch not in [1, 2] %}
  {{ exceptions.raise_compiler_error('demo_batch 只支持 1 或 2') }}
{% endif %}

with ranked as (
    select
        order_id, customer_id, order_date, lower(trim(status)) as status,
        amount, updated_at,
        row_number() over (
            partition by order_id order by updated_at desc, event_id desc
        ) as version_rank
    from {{ ref('raw_order_events') }}
    where batch_id <= {{ batch }}
)
select order_id, customer_id, order_date, status, amount, updated_at
from ranked
where version_rank = 1
