// Garmin data export → compact garmin_import.json for Cape 100 Coach.
const fs=require('fs'),path=require('path');const d=process.argv[2],out=process.argv[3];
const F=p=>JSON.parse(fs.readFileSync(path.join(d,p),'utf8'));
const ls=(dir,pre)=>fs.readdirSync(path.join(d,dir)).filter(f=>f.includes(pre)).sort();
const iso=ms=>new Date(ms).toISOString().slice(0,19);
const r1=x=>Math.round(x*10)/10, r2=x=>Math.round(x*100)/100;

const acts=F('DI-Connect-Fitness/'+ls('DI-Connect-Fitness','_summarizedActivities.json')[0])[0].summarizedActivitiesExport.map(a=>({
  id:a.activityId, name:a.name||'Activity', type:a.activityType||'other', startLocal:iso(a.startTimeLocal),
  distanceKm:r2((a.distance||0)/100000), durationMin:Math.round((a.duration||0)/60000), elevationGainM:Math.round((a.elevationGain||0)/100),
  avgHr:a.avgHr?Math.round(a.avgHr):null, maxHr:a.maxHr?Math.round(a.maxHr):null,
  trainingEffect:a.aerobicTrainingEffect!=null?r1(a.aerobicTrainingEffect):null, load:a.activityTrainingLoad!=null?r1(a.activityTrainingLoad):null,
})).sort((a,b)=>b.startLocal.localeCompare(a.startLocal));

const days={};const day=k=>days[k]||(days[k]={date:k});
for(const f of ls('DI-Connect-Aggregator','UDSFile')) for(const u of F('DI-Connect-Aggregator/'+f)){
  if(!u.calendarDate||u.calendarDate<'2021') continue;const x=day(u.calendarDate);
  if(u.totalSteps!=null) x.steps=u.totalSteps; if(u.restingHeartRate) x.restingHr=u.restingHeartRate;
  const st=u.allDayStress?.aggregatorList?.find(a=>a.type==='TOTAL')?.averageStressLevel; if(st!=null&&st>=0) x.stress=st;
  const bb=u.bodyBattery?.bodyBatteryStatList?.find(s=>s.bodyBatteryStatType==='HIGHEST')?.statsValue; if(bb!=null) x.bodyBattery=bb;
}
for(const f of ls('DI-Connect-Wellness','sleepData')) for(const s of F('DI-Connect-Wellness/'+f)){
  if(!s.calendarDate) continue;const x=day(s.calendarDate);
  const secs=(s.deepSleepSeconds||0)+(s.lightSleepSeconds||0)+(s.remSleepSeconds||0); if(secs>0) x.sleepHours=r1(secs/3600);
  if(s.sleepScores?.overallScore) x.sleepScore=s.sleepScores.overallScore;
}
for(const f of ls('DI-Connect-Metrics','TrainingReadiness')) for(const r of F('DI-Connect-Metrics/'+f)) if(r.calendarDate&&r.score!=null) day(r.calendarDate).readiness=r.score;
for(const f of ls('DI-Connect-Metrics','MaxMet')) for(const m of F('DI-Connect-Metrics/'+f)) if(m.calendarDate&&m.vo2MaxValue&&m.sport==='RUNNING') day(m.calendarDate).vo2max=m.vo2MaxValue;

const preds=ls('DI-Connect-Metrics','RunRacePredictions').flatMap(f=>F('DI-Connect-Metrics/'+f)).sort((a,b)=>a.timestamp.localeCompare(b.timestamp));
const p=preds[preds.length-1];
// Prediction 3 months ago, to show the trend.
const before=preds.filter(x=>x.calendarDate<=new Date(Date.parse(p.calendarDate)-90*864e5).toISOString().slice(0,10)).pop();
const recs=F('DI-Connect-Fitness/'+ls('DI-Connect-Fitness','_personalRecord.json')[0])[0].personalRecords
  .filter(r=>r.current&&/Run$/.test(r.personalRecordType))
  .map(r=>({type:r.personalRecordType,value:r1(r.value),date:new Date(r.prStartTimeGMT.replace(/GMT /,'')).toISOString().slice(0,10)}));
const zones=F('DI-Connect-Wellness/77675866_heartRateZones.json').find(z=>z.sport==='RUNNING')||{};
const th=ls('DI-Connect-Metrics','TrainingHistory').flatMap(f=>F('DI-Connect-Metrics/'+f)).filter(t=>t.sport==='RUNNING').pop();
const insights={
  predictionsDate:p.calendarDate, race5kSec:p.raceTime5K, race10kSec:p.raceTime10K, raceHalfSec:p.raceTimeHalf, raceMarathonSec:p.raceTimeMarathon,
  marathonSec3MonthsAgo:before?.raceTimeMarathon??null, records:recs,
  garminMaxHr:zones.maxHeartRateUsed??0, zoneFloors:[1,2,3,4,5].map(i=>zones['zone'+i+'Floor']??0),
  trainingStatus:th?.trainingStatus??'',
};
const res={activities:acts,days:Object.values(days).sort((a,b)=>a.date.localeCompare(b.date)),insights};
fs.writeFileSync(out,JSON.stringify(res));
console.log('activities',acts.length,'runs',acts.filter(a=>a.type.includes('run')).length,'days',res.days.length, res.days[0].date,'→',res.days[res.days.length-1].date);
console.log(JSON.stringify(insights));
