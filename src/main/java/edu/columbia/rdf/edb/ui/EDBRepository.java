package edu.columbia.rdf.edb.ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.jebtk.bioinformatics.annotation.Genome;
import org.jebtk.bioinformatics.annotation.Type;
import org.jebtk.core.NetworkFileException;
import org.jebtk.core.http.URLPath;
import org.jebtk.core.io.StreamUtils;
import org.jebtk.core.json.Json;
import org.jebtk.core.json.JsonParser;
import org.jebtk.core.path.Path;
import org.jebtk.core.search.SearchStackElement;

import edu.columbia.rdf.edb.EDB;
import edu.columbia.rdf.edb.EDBWLogin;
import edu.columbia.rdf.edb.Experiment;
import edu.columbia.rdf.edb.FileType;
import edu.columbia.rdf.edb.GEO;
import edu.columbia.rdf.edb.Group;
import edu.columbia.rdf.edb.Person;
import edu.columbia.rdf.edb.Sample;
import edu.columbia.rdf.edb.SampleSet;
import edu.columbia.rdf.edb.SampleTag;
import edu.columbia.rdf.edb.SampleTags;
import edu.columbia.rdf.edb.Species;
import edu.columbia.rdf.edb.Tag;
import edu.columbia.rdf.edb.VfsFile;
import edu.columbia.rdf.edb.ui.cache.CacheRepository;
import edu.columbia.rdf.edb.ui.cache.SampleAutoCache;

/**
 * Maintains a connection to a caArray server.
 *
 * @author Antony Holmes
 */
public class EDBRepository extends CacheRepository {
  private static final long serialVersionUID = 1L;

  public static final Type CHIP_SEQ_TYPE = new Type(2, "ChIP-seq");

  private final FileDownloader mFileDownloader;

  private final Vfs mVfs;

  private final URLPath mSamplesUrl;

  private final URLPath mRNASeqUrl;

  private final URLPath mRNASeqGenomesUrl;

  private final URLPath mGenomeFilessUrl;

  public EDBRepository(EDBWLogin login) throws IOException {
    super(login);

    mFileDownloader = new EDBFileDownloader(mLogin);
    mVfs = new Vfs(mLogin);
    
    
    mSamplesUrl = mLogin.getURL().join("samples");
    mRNASeqUrl = mLogin.getURL().join("rnaseq");
    mRNASeqGenomesUrl = mRNASeqUrl.join("genomes");
    mGenomeFilessUrl = mRNASeqUrl.join("files");
  }

  @Override
  public List<Sample> searchSamples(
      List<SearchStackElement> searchStack,
      Path path) {
    return null;
  }

  protected URLPath getSearchSamplesUrl() {
    return getSamplesUrl().join("search");
  }

  private URLPath getExperimentsUrl() {
    return mLogin.getURL().join("experiments");
  }

  private URLPath getExperimentsUrl(int id) {
    return getExperimentsUrl().join(id);
  }

  private URLPath getExperimentFilesUrl(int id) {
    return getExperimentsUrl(id).join("files");
  }

  private URLPath getExperimentFilesDirUrl(int id) {
    return getExperimentFilesUrl(id).join("dir");
  }

  private URLPath getSamplesUrl() {
    return mSamplesUrl;
  }
  

  private URLPath getSamplesUrl(int id) {
    return getSamplesUrl().param("sample", id).param("page", 1);
  }

  private URLPath getSampleFilesUrl(int id) {
    return getSamplesUrl(id).join("files");
  }

  private URLPath getSampleTagsUrl(int id) {
    return getSamplesUrl(id).join("tags");
  }

  private URLPath getSampleTagUrl(int sampleId, int tagId) {
    return getSampleTagsUrl(sampleId).param("tag", tagId);
  }

  private URLPath getSamplePersonsUrl(int sampleId) {
    return getSamplesUrl(sampleId).join("persons");
  }

  private URLPath getSampleGeoUrl(Sample sample) {
    return getSampleGeoUrl(sample.getId());
  }

  private URLPath getSampleGeoUrl(int id) {
    return getSamplesUrl(id).join("geo");
  }

  private URLPath getSamplesUrl(String name) {
    return getSamplesUrl().join("alias").join(name);
  }

  @Override
  public SearchResults searchSamples(String query,
      Path path,
      Collection<Person> persons,
      Collection<Type> dataTypes,
      Collection<Species> organisms,
      Collection<Group> groups,
      Collection<SampleSet> sets,
      int page) throws IOException {
    URLPath url = getSearchSamplesUrl().param("p", path.toString())
        .param("q", query);
    // .param("v", "all")
    // .param("m", 1000)

    if (!dataTypes.isEmpty()) {
      for (Type d : dataTypes) {
        url = url.param("type", d.getId());
      }
    }

    if (!organisms.isEmpty()) {
      for (Type o : organisms) {
        url = url.param("o", o.getId());
      }
    }

    if (!groups.isEmpty()) {
      for (Type g : groups) {
        url = url.param("group", g.getId());
      }

      //if (groups.getAllMode()) {
      //  url = url.param("gm", "all");
      //}
    }
    
    if (!sets.isEmpty()) {
      for (Type s : sets) {
        url = url.param("set", s.getId());
      }

      //if (groups.getAllMode()) {
      //  url = url.param("gm", "all");
      //}
    }
    
    if (!persons.isEmpty()) {
      for (Type p : persons) {
        url = url.param("person", p.getId());
      }
    }
    
    url = url.param("page", page);

    System.err.println(url + " " + this);

    Json json = new JsonParser().parse(url);

    SearchResults ret = parseSampleJson(json);

    return ret;
  }

  @Override
  public SearchResults parseSampleJson(Json json) {
    return parseSampleJson(json, SearchMode.FULL);
  }

  public SearchResults parseSampleJson(Json json, SearchMode searchMode) {
    List<Sample> samples = new ArrayList<>(1000);

    Json sampleJSON;
    Json tempJSON;

    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
    
    int page = json.getInt("page");
    int pages = json.getInt("pages");
    
    json = json.get("results");

    for (int i = 0; i < json.size(); ++i) {
      sampleJSON = json.get(i);

      int sampleId = sampleJSON.get(EDB.HEADING_ID).getInt();

      Experiment experiment = mExperiments
          .get(sampleJSON.get(EDB.HEADING_EXPERIMENT).getInt());

      Type expression = mDataTypes
          .get(sampleJSON.get(EDB.HEADING_TYPE).getInt());

      Species species = mSpecies
          .get(sampleJSON.get(EDB.HEADING_ORGANISM).getInt());

      String name = sampleJSON.get(EDB.HEADING_NAME_SHORT).getString();

      // String description = sampleJSON.get("description").getString();

      Date date = null;

      try {
        date = formatter.parse(sampleJSON.get(EDB.HEADING_DATE).getString());
      } catch (ParseException e) {
        e.printStackTrace();
      }

      // System.err.println(name + " " + state);

      /*
       * SampleStateType stateType = mSampleStates.get(sampleId);
       * 
       * if (stateType != null) { state = stateType.getType(); } else { state =
       * SampleState.UNLOCKED; }
       */

      Sample sample = new SampleAutoCache(sampleId, experiment, expression,
          name, species, date);

      tempJSON = sampleJSON.get("geo");

      if (tempJSON != null) {
        GEO geo = new GEO(tempJSON.get(EDB.HEADING_ID).getInt(),
            tempJSON.get("geo_series_accession").getString(),
            tempJSON.get("geo_accession").getString(),
            tempJSON.get("geo_platform").getString());

        sample.setGEO(geo);
      }

      //
      // Tags
      //

      /*
       * Json tagsJson = sampleJSON.get("tags");
       * 
       * if (tagsJson != null) { for (int j = 0; j < tagsJson.size(); ++j) {
       * Json tagJson = tagsJson.get(j);
       * 
       * int id = tagJson.get("id").getInt();
       * 
       * Tag field = mTags.get(id);
       * 
       * SampleTag sampleTag = new SampleTag(id, field,
       * tagJson.get("value").getString());
       * 
       * //System.err.println("tags " + field.toString());
       * //System.err.println("tags " + tagJson.get("value").getString());
       * 
       * sample.getTags().add(sampleTag); } }
       */

      //
      // Groups
      //

      Json groupsJson = sampleJSON.get(EDB.HEADING_GROUPS);

      if (groupsJson != null) {
        List<Group> groups = new ArrayList<>(10);

        for (int j = 0; j < groupsJson.size(); ++j) {
          int id = groupsJson.get(j).getInt();

          Group group = mGroups.get(id);

          groups.add(group);
        }

        // Sort the groups
        Collections.sort(groups);

        sample.getGroups().addAll(groups);
      }

      //
      // Add persons
      //

      for (Person person : mSamplePersons.get(sampleId)) {
        sample.getPersons().add(person);
      }

      /*
       * Json personsJson = sampleJSON.get("persons");
       * 
       * if (personsJson != null) { for (int j = 0; j < personsJson.size(); ++j)
       * { Json personJSON = personsJson.get(j);
       * 
       * Person person = mPersons.get(personJSON.get("id").getInt());
       * 
       * sample.getPersons().add(person); } }
       */

      //
      // Add files
      //

      /*
       * Json filesJson = sampleJSON.get("files");
       * 
       * if (filesJson != null) { for (int j = 0; j < filesJson.size(); ++j) {
       * Json fileJSON = filesJson.get(j);
       * 
       * String fileName = fileJSON.get("name").getString();
       * 
       * FileDescriptor file = new FileDescriptor(fileJSON.getInt("id"),
       * fileName, FileType.parse(fileJSON.get("type").getInt()),
       * formatter.parse(fileJSON.get("created").getString()));
       * 
       * sample.getFiles().add(file); } }
       */

      // cacheGEO(sample);

      // for (SectionType sectionType : SectionType.values()) {
      // cacheSampleSection(sample, sectionType);
      // }

      samples.add(sample);
    }

    return new SearchResults(samples, new SearchMetaData(page, pages));
  }

  @Override
  public VfsFile getExperimentFilesDir(int experimentId)
      throws IOException {
    URLPath url = getExperimentFilesDirUrl(experimentId);

    return getFiles(url, true).get(0);
  }

  @Override
  public List<VfsFile> getExperimentFiles(int experimentId)
      throws IOException {
    URLPath url = getExperimentFilesUrl(experimentId);

    return getFiles(url);
  }

  @Override
  public List<VfsFile> getSampleFiles(int sampleId) throws IOException {
    URLPath url = getSampleFilesUrl(sampleId);

    List<VfsFile> files = getFiles(url);

    return files;
  }

  private static List<VfsFile> getFiles(URLPath url) throws IOException {
    return getFiles(url, false);
  }

  /**
   * List files and or directories from a URL
   * 
   * @param url
   * @param showDirs
   * @return
   * @throws IOException
   * @throws ParseException
   */
  private static List<VfsFile> getFiles(URLPath url, boolean showDirs)
      throws IOException {
    System.err.println(url);

    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");

    List<VfsFile> files = new ArrayList<>();

    Json json = new JsonParser().parse(url);

    for (int j = 0; j < json.size(); ++j) {
      Json fileJSON = json.get(j);

      String fileName = fileJSON.getString(EDB.HEADING_NAME_SHORT);

      Date date = null;

      try {
        date = formatter.parse(fileJSON.getString(EDB.HEADING_DATE));
      } catch (ParseException e) {
        e.printStackTrace();
      }

      VfsFile file = new VfsFile(
          fileJSON.getInt(EDB.HEADING_ID), fileName,
          FileType.parse(fileJSON.getInt(EDB.HEADING_TYPE)), date);

      // Don't show directories
      if (showDirs || file.getType() == FileType.FILE) {
        files.add(file);
      }
    }

    return files;
  }

  @Override
  public void cachePersons(int sampleId, Collection<Person> persons)
      throws IOException {
    URL url = getSamplePersonsUrl(sampleId).toURL(); // ("p",
                                                     // path.toString()).addParam("q",
                                                     // query).toUrl();

    //System.err.println("person:" + url);

    Json json = new JsonParser().parse(url);

    if (json != null) {
      Json sampleJson = json.get("samples");
      
      for (int j = 0; j < sampleJson.size(); ++j) {
        Json personJSON = sampleJson.get(j);

        Person person = mPersons.get(personJSON.getInt("id"));

        persons.add(person);
      }
    }
  }

  /**
   * Cache all of the annotation sections associated with the samples.
   * 
   * @param sample
   * @return
   * @throws IOException
   */
  @Override
  public SampleTags cacheSampleFields(Sample sample) throws IOException {
    return null;
  }

  public void downloadFile(URL urlFile, File localFile)
      throws NetworkFileException, IOException {

    FileOutputStream output = new FileOutputStream(localFile);

    try (InputStream input = urlFile.openStream()) {
      StreamUtils.copy(input, output);
    } finally {
      output.close();
    }
  }

  @Override
  public FileDownloader getFileDownloader()
      throws UnsupportedEncodingException {
    return mFileDownloader;
  }

  @Override
  public Sample getSample(int id) throws IOException {
    URLPath url = getSamplesUrl(id); // ("p", path.toString()).addParam("q",
                                         // query).toUrl();

    System.err.println(url);

    Json json = new JsonParser().parse(url);

    SearchResults res = parseSampleJson(json);

    if (!res.samples.isEmpty()) {
      return res.samples.get(0);
    } else {
      return null;
    }
  }

  @Override
  public Sample getSample(String name) throws IOException {
    URLPath url = getSamplesUrl(name); // ("p",
                                           // path.toString()).addParam("q",
                                           // query).toUrl();

    Json json = new JsonParser().parse(url);

    SearchResults res = parseSampleJson(json);

    if (!res.samples.isEmpty()) {
      return res.samples.get(0);
    } else {
      return null;
    }
  }

  @Override
  public void cacheTags(int sampleId, SampleTags tags) throws IOException {
    cacheTag(getSampleTagsUrl(sampleId), tags);
  }

  @Override
  public void cacheTag(int sampleId, int tagId, SampleTags tags)
      throws IOException {
    cacheTag(getSampleTagUrl(sampleId, tagId), tags);
  }
  
  private void cacheTag(URLPath url, SampleTags tags) throws IOException {
    
    //Splitter split = Splitter.on(TextUtils.COLON_DELIMITER).limit(2);
    
    //url = url.param("format", "text");
    
    //System.err.println(url);
    
    /*
    
    
    BufferedReader reader = URLUtils.newBufferedReader(url);

    String line;
    List<String> tokens;
    int id;
    String value;
    
    while ((line = reader.readLine()) != null) {
      tokens = split.text(line);
      
      id = Integer.parseInt(tokens.get(0));
      value = tokens.get(1);

      Tag field = mTags.get(id);

      SampleTag sampleTag = new SampleTag(id, field,
          value);
      
      tags.add(sampleTag);
    }
    */
    
    
    Json json = new JsonParser().parse(url.toURL());

    for (int i = 0; i < json.size(); ++i) {
      Json tagJson = json.get(i);

      int id = tagJson.getInt(EDB.HEADING_ID);

      Tag field = mTags.get(id);

      SampleTag sampleTag = new SampleTag(id, field, tagJson.getString("v"));

      tags.add(sampleTag);
    }
  }

  @Override
  public void cacheGEO(Sample sample) throws IOException {
    URLPath url = getSampleGeoUrl(sample);

    // System.err.println(url);

    Json json = new JsonParser().parse(url);

    if (json.size() > 0) {
      json = json.get(0);

      GEO geo = new GEO(json.get("id").getInt(),
          json.get("series_accession").getString(),
          json.get("accession").getString(),
          json.get("platform").getString());

      sample.setGEO(geo);
    }
  }

  @Override
  public Collection<Person> getPersons() {
    return mPersons.getValues();
  }
  
  @Override
  public Collection<Type> getDataTypes() {
    return mDataTypes.getValues();
  }
  

  @Override
  public Collection<Species> getOrganisms() {
    return mSpecies.getValues();
  }
  
  @Override
  public Iterable<Genome> getGenomes() {
    return mGenomes.getValues();
  }
  
  @Override
  public Iterable<SampleSet> getSets() {
    return mSets.getValues();
  }
  
  @Override
  public Iterable<Genome> getGenomes(int sid) throws IOException {
    URLPath url = mRNASeqGenomesUrl.param("sid", sid);
    
    Json json = new JsonParser().parse(url);
    
    List<Genome> ret = new ArrayList<>();

    for (int i = 0; i < json.size(); ++i) {
      Json sectionTypeJSON = json.get(i);

      int id = sectionTypeJSON.getInt(EDB.HEADING_ID);
      String name = sectionTypeJSON.getString(EDB.HEADING_NAME_SHORT);
      
      ret.add(new Genome(id, name));
    }
    
    return ret;
  }
  
  @Override
  public Iterable<VfsFile> getGenomeFiles(int sid, int gid) throws IOException {
    URLPath url = mGenomeFilessUrl.param("sid", sid).param("gid", gid);
    
    return getFiles(url);
  }

  /*
   * @Override public List<Peaks> searchChipSeqPeaks(Sample sample) throws
   * ParseException, IOException, ParseException, java.text.ParseException { URL
   * url = getChipSeqPeaksUrl(sample).toUrl();
   * 
   * Json json = new JsonParser().parse(url);
   * 
   * List<Peaks> allPeaks = new ArrayList<Peaks>();
   * 
   * //System.err.println(url);
   * 
   * Json peaksJson;
   * 
   * for (int i = 0; i < json.size(); ++i) { peaksJson = json.get(i);
   * 
   * int id = peaksJson.get("id").getInt();
   * 
   * String name = peaksJson.get("name").getString();
   * 
   * String genome = peaksJson.get("genome").getString();
   * 
   * int readLength = peaksJson.get("read_length").getInt();
   * 
   * String peakCaller = peaksJson.get("peak_caller").getString();
   * 
   * String peakCallerParameters =
   * peaksJson.get("peak_caller_parameters").getString();
   * 
   * SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd"); Date date
   * = formatter.parse(peaksJson.get("release_date").getString());
   * 
   * Peaks peaks = new Peaks(id, name, genome, readLength, peakCaller,
   * peakCallerParameters, date);
   * 
   * Json locationsJson = peaksJson.get("peak_locations");
   * 
   * for (int j = 0; j < locationsJson.size(); ++j) { Json locationJson =
   * locationsJson.get(j);
   * 
   * UCSCTrackRegion r = new
   * UCSCTrackRegion(Chromosome.parse(locationJson.get("c").getString()),
   * locationJson.get("s").getInt(), locationJson.get("e").getInt());
   * 
   * peaks.getRegions().add(r); }
   * 
   * allPeaks.add(peaks); }
   * 
   * return allPeaks; }
   */

  @Override
  public Vfs vfs() {
    return mVfs;
  }

}
