import redis
import datetime

client = redis.Redis(host='192.168.33.20', port=6379, password='openwhisk')
first_time = datetime.datetime.now()
print(client.get('mem0'))
second_time = datetime.datetime.now()
print((second_time - first_time).microseconds / 1000)
print(client.get('cpu1'))
third_time = datetime.datetime.now()
print((third_time - second_time).microseconds / 1000)
print(client.get('cpu0'))
print(client.get('mem1'))
fourth_time = datetime.datetime.now()
print((fourth_time - third_time).microseconds / 1000)
