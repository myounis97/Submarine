# Submarine

Submarine is an Android library for loading MediaStore using [Copper](https://github.com/cashapp/copper) Library, like Image, Video and Audio from local storage.

## [Copper](https://github.com/cashapp/copper)
A content provider wrapper for reactive queries with Kotlin coroutines Flow or RxJava Observable.

### Download 

```
implementation 'com.myounis.submarine:submarine:1.0.0'
```

### Initialization

```java
Submarine submarine = new Submarine.Builder(requireContext())
                .setPageSize(PAGE_SIZE)
                .build();
```

### Load Albums

```java
 disposables.add(submarine.loadAlbums(GalleryType.IMAGES)
                .subscribeOn(Schedulers.io())
                .map(HashSet::new)
                .map(ArrayList::new)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(albums -> {
                
                  //Your logic
                
                }));
```

### Load images and video

```java
disposables.add(submarine.loadImagesAndVideos(pageNumber, album)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(mdeiaList -> {

                   //Your logic

                }));
```                
             
### Load Media By Uri

```java
 long id = ContentUris.parseId(uri);

        if (id == -1) return;

        disposables.add(submarine.loadMedia(id)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(baseMedia -> {
                
                   //Your logic
                
                });
```



