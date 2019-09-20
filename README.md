i Eclipse: Export as "Runnable JAR File"

initiel tar bygges på udvikler maskine: ant archive-test1
uploades til /pack/temp: 				scp fmk-data-updater-eclipse.tar chj@app01.test1.fmk.netic.dk:/pack/temp
udpakkes: 								tar -xf fmk-data-updater-eclipse.tar

Pak tar fra /pack/temp/fmk-data-updater med stage-propertyfiler, gemmes i /pack/temp/stage/fmk-data-updater-eclipse.tar:
tar -cf /pack/temp/stage/fmk-data-updater-eclipse.tar fmk-data-updater-eclipse_lib/* fmk-data-updater-eclipse.jar stage/*.properties *.js
 
Pak tar fra /pack/temp/fmk-data-updater med prod-propertyfiler, gemmes i /pack/temp/prod/fmk-data-updater-eclipse.tar:
tar -cf /pack/temp/prod/fmk-data-updater-eclipse.tar fmk-data-updater-eclipse_lib/* fmk-data-updater-eclipse.jar prod/*.properties *.js

Køres således: 
java -cp "stage:fmk-data-updater-eclipse_lib:fmk-data-updater-eclipse.jar" dk.medicinkortet.dataupdater.Main dosageenddate test

Eller i test1 udgaven (da .properties filerne ligger sammen med jar'en):
java -cp ".:fmk-data-updater-eclipse_lib:fmk-data-updater-eclipse.jar" dk.medicinkortet.dataupdater.Main dosageenddate test

