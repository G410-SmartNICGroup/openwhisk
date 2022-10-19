FROM scala

ENV UID=1001 \
    NOT_ROOT_USER=owuser

# Copy app jars
ADD build/distributions/scheduler.tar /

COPY init.sh /
RUN chmod +x init.sh

RUN adduser --disabled-password --uid ${UID} --home /home/${NOT_ROOT_USER} --shell /bin/bash ${NOT_ROOT_USER}
USER ${NOT_ROOT_USER}

EXPOSE 8080
CMD ["./init.sh", "0"]      
