wget https://wordnetcode.princeton.edu/wn3.1.dict.tar.gz
tar -zxvf wn3.1.dict.tar.gz
mv dict wordnet
wget https://git.rwth-aachen.de/coscine/research/pikesdocker/-/archive/main/pikesdocker-main.tar.gz?path=models
tar -zxvf pikesdocker-main.tar.gz?path=models
mv pikesdocker-main-models/models models
wget https://git.rwth-aachen.de/coscine/research/pikesdocker/-/archive/main/pikesdocker-main.tar.gz?path=ukb
tar -zxvf pikesdocker-main.tar.gz?path=ukb
mv pikesdocker-main-ukb/ukb ukb
