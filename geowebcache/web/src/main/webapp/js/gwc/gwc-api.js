/*
 * 
 * 
 * Relevant ExtJS 4 documentation:
 * - http://all-docs.info/extjs4/docs/api/Ext.data.Model.html
 */

Ext.define('GWC.RestService', {
    
    config: {
        disableCaching: true,
        endpoint: 'http://localhost:8080/geowebcache/rest',
        timeout: 60000,
        jobStore: null
    },
    
    constructor: function(config) {
    	this.initConfig(config);
    	
    	Ext.define('Job', {
    		extend: 'Ext.data.Model',
    	    fields: [
    	        {name: 'jobId',  						type: 'long'},
    	        {name: 'layerName',						type: 'string'},
    	        {name: 'state',							type: 'string'},
    	        {name: 'timeSpent',						type: 'long'},
    	        {name: 'timeRemaining',					type: 'long'},
    	        {name: 'tilesDone',						type: 'long'},
    	        {name: 'tilesTotal',					type: 'long'},
    	        {name: 'failedTileCount',				type: 'long'},
    	        {name: 'bounds',						type: 'string'},
    	        {name: 'gridSetId',						type: 'string'},
    	        {name: 'srs',							type: 'int'},
    	        {name: 'threadCount',					type: 'int'},
    	        {name: 'zoomStart',						type: 'int'},
    	        {name: 'zoomStop',						type: 'int'},
    	        {name: 'format',						type: 'format'},
    	        {name: 'jobType',						type: 'string'},
    	        {name: 'maxThroughput',					type: 'int'},
    	        {name: 'priority',						type: 'string'},
    	        {name: 'schedule',						type: 'string'},
    	        {name: 'timeFirstStart',				type: 'date'},
    	        {name: 'timeLatestStart',				type: 'date'}
    	    ],
    	    proxy: {
    			type: 'rest',
    			url : this.endpoint + '/jobs.json',
                reader: {
                	root: 'jobs'
                	// totalProperty: 'totalCount' // if we are doing pagination
            	}
    		}
    	});
    	
    	this.jobStore = new Ext.data.Store({
    	    model: 'Job',
    	    pageSize: 0
    	});

    	return this;
    },

	loadJobs: function(callback, failurecallback) {
    	this.jobStore.load();
	}	
});