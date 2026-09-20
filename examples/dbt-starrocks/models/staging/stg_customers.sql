-- staging 统一字段类型；实际项目可把 seed 的 ref 替换为外部已加载表的 source。
select customer_id, trim(customer_name) as customer_name, trim(city) as city
from {{ ref('raw_customers') }}
