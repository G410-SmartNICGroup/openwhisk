import threading
from time import sleep
import requests
import redis

idx2ip = {
    0: '192.168.33.10',
    1: '192.168.33.20'
}
invokerNum = 2
redisHost = '192.168.33.20'
redisPort = 6379
redisPassword = 'openwhisk'
sleepInterval = 1

class myThread(threading.Thread):
    def __init__(self, idx):
        threading.Thread.__init__(self)
        self.__stop = False
        self.__idx = idx
        self.__redisClient = redis.Redis(host=redisHost, port=redisPort, password=redisPassword)

    def stop(self):
        self.__stop = True

    def run(self):
        baseUrl = "http://" + idx2ip[self.__idx] + ":10086/"
        cpuUrl = baseUrl + "getVmCPU"
        memUrl = baseUrl + "getVmMemory"
        cpuIdx = 'cpu' + str(self.__idx)
        memIdx = 'mem' + str(self.__idx)
        while self.__stop is False:
            ret = requests.get(url=cpuUrl).text
            ret = ret.split(':')
            ret = ret[1]
            self.__redisClient.set(cpuIdx, ret[:-1])
            ret = requests.get(url=memUrl).text
            ret = ret.split(':')
            ret = ret[1]
            self.__redisClient.set(memIdx, ret[:-1])
            sleep(sleepInterval)
    
if __name__ == "__main__":
    threadList = []
    for i in range(0, invokerNum):
        myThr = myThread(i)
        threadList.append(myThr)
    try:
        for myThr in threadList:
            myThr.start()
        while True:
            # permanently run
            sleep(114514)
    except KeyboardInterrupt:
        for myThr in threadList:
            myThr.stop()
        for myThr in threadList:
            myThr.join()
        exit(0)