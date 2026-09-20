{{ config(
    materialized='incremental',
    incremental_strategy='default',
    table_type='PRIMARY',
    keys=['order_id'],
    unique_key='order_id',
    distributed_by=['order_id']
) }}

-- StarRocks 用 PRIMARY KEY 表的写入语义覆盖旧订单；keys 是真正的物理主键。
-- unique_key 声明 dbt 逻辑唯一性，不能代替 keys 或唯一性数据测试。
select
    order_id, customer_id, order_date, status, amount, updated_at,
    cast(case when status = 'paid' then amount else 0 end as decimal(18,2)) as paid_amount
from {{ ref('stg_orders') }}
{% if is_incremental() %}
-- 包含水位边界，接住与上一批最大时间相同的新记录；主键覆盖保证重读幂等。
-- 这不处理早于水位的迟到数据或源端物理删除；需要另设回看窗口/CDC/全量重建。
where updated_at >= (
    select coalesce(max(updated_at), cast('1900-01-01 00:00:00' as datetime))
    from {{ this }}
)
{% endif %}
