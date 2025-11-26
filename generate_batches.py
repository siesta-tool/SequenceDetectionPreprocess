#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Created on Thu Apr 27 08:41:43 2023

@author: mavroudo
"""

#This script generates multiple datasets from a single real world dataset
#where each time only a portion of the traces are carried on to the next file
#while for the rest we assign a new trace id. Additionally, for each file
#we will use multi-threading to speed-up the process


import pm4py
from datetime import datetime,timedelta
import sys
import random
from threading import Thread,Lock

carry_on_traces=0.1 #10%

def transform_original_log(log,diff_days,want_days,min_ts,max_ts):
    for t in log:
        for e in t:
            t_prev=e["time:timestamp"]
            e["time:timestamp"]=datetime.fromtimestamp((t_prev.timestamp()-min_ts.timestamp())/diff_days*want_days+min_ts.timestamp())
    for t in log:
        for index,e in enumerate(t[:-1]):
            diff=(t[index+1]["time:timestamp"]-t[index]["time:timestamp"])
            if 0 < diff.seconds < 60 and diff.days==0:
                t[index+1]["time:timestamp"]+=timedelta(minutes=1)
            elif diff.seconds<0:
                t[index+1]["time:timestamp"]+=timedelta(minutes=2)
    return len(log),[i for i in range(0,len(log))]
                
def create_additional_log(log,want_days,prev_traces:list,log_index:int, output_file:str):
# =============================================================================
#     Caclulate the new trace numbers
# =============================================================================
    if log_index==0:
        traces_numbers=prev_traces
    else:
        traces_numbers=random.choices(prev_traces,k=int(carry_on_traces*len(prev_traces)))
        t=max(prev_traces)
        while(len(traces_numbers)<len(prev_traces)):
            traces_numbers.append(t+1)
            t+=1
    
# =============================================================================
#  Create the traces and output file but with threads
# =============================================================================
    threads=[]
    shared_list=[]
    for t,t_index in zip(log,traces_numbers):
        t=Thread(target=handle_Thread,args=(t,want_days,log_index,t_index,shared_list))
        threads.append(t)
        t.start()
    for thread in threads:
        thread.join()
    with open(output_file,'w') as fout:
        for line in shared_list:
            fout.write(line)
    return traces_numbers

def handle_Thread(trace:pm4py.objects.log.obj.Trace,want_days:int,log_index:int,trace_number:int,shared_list):
    transf = []
    for e in trace:
        ts = e["time:timestamp"]+timedelta(days=log_index*want_days)
        transf.append("{}/delab/{}".format(e['concept:name'],ts.strftime("%Y-%m-%d %H:%M:%S")))
    shared_list.append("{}::{}\n".format(str(trace_number),",".join(transf)))

if __name__=="__main__":
# =============================================================================
#     Loading parameters
# =============================================================================
    logfile= sys.argv[1]
    want_days= int(sys.argv[2])
    num_logs= int(sys.argv[3])
    log=pm4py.read_xes(logfile)
# =============================================================================
#     Transforming original trace to scale into the want_days parameter
# =============================================================================
    timestamps=[i["time:timestamp"] for j in log for i in j]
    min_ts=min(timestamps)
    max_ts=max(timestamps)
    diff_days = (max_ts-min_ts).days +1
    num_traces,prev_traces = transform_original_log(log, diff_days, want_days, min_ts, max_ts)
    print(num_traces,prev_traces)
# =============================================================================
#     Main Loop for the num_logs
# =============================================================================
    for n in range(num_logs):
        log_name = 'output/'+logfile.split("/")[-1].split(".")[0]+"_"+str(n)+".withTimestamp"
        prev_traces=create_additional_log(log, want_days, prev_traces, n, log_name)
        
        
