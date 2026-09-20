-- 客户维度独立存放地区；查询按地区统计收入时由 MetricFlow 推导关联。
select customer_id, customer_name, region, segment
from {{ ref('raw_customers') }}
