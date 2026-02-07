-- 模拟用户数据
INSERT INTO users (username, email, phone) VALUES ('张三', 'zhangsan@example.com', '13800138001');
INSERT INTO users (username, email, phone) VALUES ('李四', 'lisi@example.com', '13800138002');
INSERT INTO users (username, email, phone) VALUES ('王五', 'wangwu@example.com', '13800138003');

-- 模拟商品数据
INSERT INTO products (name, category, price, stock, description) VALUES ('MacBook Pro 14寸', '电脑', 14999.00, 50, 'Apple M3 Pro芯片，18GB内存');
INSERT INTO products (name, category, price, stock, description) VALUES ('iPhone 16 Pro', '手机', 8999.00, 200, 'A18 Pro芯片，256GB存储');
INSERT INTO products (name, category, price, stock, description) VALUES ('AirPods Pro 2', '耳机', 1899.00, 500, '主动降噪，USB-C充电');
INSERT INTO products (name, category, price, stock, description) VALUES ('iPad Air', '平板', 4799.00, 100, 'M2芯片，10.9英寸');
INSERT INTO products (name, category, price, stock, description) VALUES ('Apple Watch Ultra 2', '手表', 5999.00, 80, '钛金属表壳，双频GPS');

-- 模拟订单数据
INSERT INTO orders (order_no, user_id, product_id, quantity, total_amount, status, shipping_address, tracking_no) VALUES ('ORD20250201001', 1, 1, 1, 14999.00, 'DELIVERED', '北京市朝阳区xxx路1号', 'SF1234567890');
INSERT INTO orders (order_no, user_id, product_id, quantity, total_amount, status, shipping_address, tracking_no) VALUES ('ORD20250203002', 1, 3, 2, 3798.00, 'SHIPPED', '北京市朝阳区xxx路1号', 'YT9876543210');
INSERT INTO orders (order_no, user_id, product_id, quantity, total_amount, status, shipping_address, tracking_no) VALUES ('ORD20250205003', 2, 2, 1, 8999.00, 'PENDING', '上海市浦东新区yyy路2号', NULL);
INSERT INTO orders (order_no, user_id, product_id, quantity, total_amount, status, shipping_address, tracking_no) VALUES ('ORD20250206004', 2, 4, 1, 4799.00, 'SHIPPED', '上海市浦东新区yyy路2号', 'ZT1122334455');
INSERT INTO orders (order_no, user_id, product_id, quantity, total_amount, status, shipping_address, tracking_no) VALUES ('ORD20250207005', 3, 5, 1, 5999.00, 'DELIVERED', '广州市天河区zzz路3号', 'SF5566778899');
INSERT INTO orders (order_no, user_id, product_id, quantity, total_amount, status, shipping_address, tracking_no) VALUES ('ORD20250207006', 3, 2, 1, 8999.00, 'CANCELLED', '广州市天河区zzz路3号', NULL);

-- 模拟退款数据
INSERT INTO refunds (refund_no, order_no, reason, amount, status) VALUES ('REF20250207001', 'ORD20250207006', '不想要了', 8999.00, 'APPROVED');
